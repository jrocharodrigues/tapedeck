package com.impecabel.tapedeck.macrobenchmark

import com.impecabel.tapedeck.core.Tapedeck
import com.impecabel.tapedeck.core.matcher.RequestMatcher
import com.impecabel.tapedeck.core.model.RecordedExchange
import com.impecabel.tapedeck.core.model.RecordedRequest
import com.impecabel.tapedeck.core.model.RecordedResponse
import com.impecabel.tapedeck.core.model.Recording
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Records and replays the requests the app's `ReplayInterceptor` sends to Tapedeck's server.
 *
 * With [followRedirects] (the default), [forwardingClient] follows the backend's redirects, and the final response is
 * recorded and replayed for the original URL: the app never sees the 3xx. Without it, each 3xx is recorded and replayed
 * as-is, and its `Location` is served pointing back at this server ([Tapedeck.REDIRECT_PATH], with the real target),
 * so that the app's own redirect handling sends every hop through Tapedeck too, where it's recorded and replayed like
 * any other request. (OkHttp follows redirects below application interceptors, so the follow-up request never passes
 * through `ReplayInterceptor`.) Recordings keep the backend's original `Location`.
 */
class TapedeckDispatcher(
    val recording: Recording = Recording(),
    val matcher: RequestMatcher,
    val forwardingClient: OkHttpClient = OkHttpClient.Builder().build(),
    mode: TapedeckMode = TapedeckMode.RECORD,
    val latencyMs: Long = 100L,
    val recordServerErrors: Boolean = false,
    val onUnmatched: OnUnmatched = OnUnmatched.FORWARD,
    val followRedirects: Boolean = true
) : Dispatcher() {

    private val logger = Logger.getLogger("TapedeckDispatcher")

    private val client: OkHttpClient = forwardingClient.newBuilder()
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)
        .build()

    private val warnedAboutRedirects = AtomicBoolean(false)

    // Switched by the test thread between launches, read by MockWebServer's threads
    @Volatile
    var mode: TapedeckMode = mode

    val recordedCount = AtomicInteger(0)
    val replayedCount = AtomicInteger(0)
    val forwardedCount = AtomicInteger(0)
    val unmatchedCount = AtomicInteger(0)

    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
        val serverUrl = request.requestUrl
        // A redirect hop sent back here (see followRedirects): its target is in the URL, not in the header, which still
        // holds the first request's URL
        val redirectTarget = serverUrl?.takeIf { it.encodedPath == Tapedeck.REDIRECT_PATH }
            ?.queryParameter(Tapedeck.REDIRECT_URL_PARAMETER)
        val originalUrl = redirectTarget
            ?: request.getHeader(Tapedeck.HEADER_ORIGINAL_URL)
            ?: serverUrl?.toString()
            ?: "http://127.0.0.1"

        var headers = request.headers.toMultimap()
        if (redirectTarget != null) {
            // Like OkHttp following a redirect: no credentials to another host
            val fromHost = serverUrl?.queryParameter(Tapedeck.REDIRECT_FROM_PARAMETER)
            if (!fromHost.equals(redirectTarget.toHttpUrlOrNull()?.host, ignoreCase = true)) {
                headers = headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }
            }
        }

        val bodyBytes = request.body.readByteArray()
        val incoming = RecordedRequest(
            method = request.method ?: "GET",
            url = originalUrl,
            headers = headers,
            body = if (bodyBytes.isNotEmpty()) bodyBytes else null
        )

        return when (mode) {
            TapedeckMode.RECORD -> handleRecord(incoming, serverUrl)
            TapedeckMode.REPLAY -> handleReplay(incoming, serverUrl)
            TapedeckMode.PASSTHROUGH -> {
                forwardedCount.incrementAndGet()
                respond(forwardToNetwork(incoming), incoming, serverUrl)
            }
        }
    }

    private fun handleRecord(incoming: RecordedRequest, serverUrl: HttpUrl?): MockResponse {
        val forwarded = forwardToNetwork(incoming)
        val response = forwarded.response
        if (response == null) {
            // The backend wasn't reached: nothing to record
            return respond(forwarded, incoming, serverUrl)
        }

        if (response.isServerError && !recordServerErrors) {
            logger.log(Level.WARNING, "Tapedeck: Server error ${response.code} for ${incoming.url} not recorded.")
            return respond(forwarded, incoming, serverUrl)
        }

        recording.add(RecordedExchange(durationMs = forwarded.durationMs, request = incoming, response = response))
        recordedCount.incrementAndGet()
        return respond(forwarded, incoming, serverUrl)
    }

    private fun handleReplay(incoming: RecordedRequest, serverUrl: HttpUrl?): MockResponse {
        val matched = recording.find(incoming, matcher)

        if (matched != null) {
            replayedCount.incrementAndGet()
            val mockResp = serve(matched.response, incoming, serverUrl)
            if (latencyMs > 0) {
                mockResp.setHeadersDelay(latencyMs, TimeUnit.MILLISECONDS)
            }
            return mockResp
        }

        unmatchedCount.incrementAndGet()
        logger.log(Level.WARNING, "Tapedeck: Unmatched request for ${incoming.url} in REPLAY mode.")

        return when (onUnmatched) {
            OnUnmatched.FORWARD -> {
                forwardedCount.incrementAndGet()
                respond(forwardToNetwork(incoming), incoming, serverUrl)
            }
            OnUnmatched.FAIL -> {
                MockResponse()
                    .setResponseCode(500)
                    .setBody("Tapedeck: Unmatched request failed for ${incoming.url}")
            }
            OnUnmatched.NOT_FOUND -> {
                MockResponse()
                    .setResponseCode(404)
                    .setBody("Tapedeck: 404 Not Found")
            }
        }
    }

    private class Forwarded(val response: RecordedResponse?, val error: Exception?, val durationMs: Long)

    private fun respond(forwarded: Forwarded, incoming: RecordedRequest, serverUrl: HttpUrl?): MockResponse =
        forwarded.response?.let { serve(it, incoming, serverUrl) }
            ?: MockResponse()
                .setResponseCode(502)
                .setBody("Tapedeck Bad Gateway: ${forwarded.error?.message}")

    /** [response] as served to the app: a redirect's Location points back at this server (see [followRedirects]). */
    private fun serve(response: RecordedResponse, incoming: RecordedRequest, serverUrl: HttpUrl?): MockResponse {
        val mockResp = response.toMockResponse(incoming.method)
        if (followRedirects || response.code !in 300..399 || serverUrl == null) return mockResp
        val location = response.headers.entries
            .firstOrNull { it.key.equals("Location", ignoreCase = true) }?.value?.firstOrNull() ?: return mockResp
        val from = incoming.url.toHttpUrlOrNull() ?: return mockResp
        val target = from.resolve(location) ?: return mockResp

        if (warnedAboutRedirects.compareAndSet(false, true)) {
            logger.log(
                Level.WARNING,
                "Tapedeck: passing redirect ${response.code} ${incoming.url} -> $target through to the app " +
                    "(followRedirects = false); its Location is served pointing back at Tapedeck."
            )
        }
        val throughTapedeck = serverUrl.newBuilder()
            .encodedPath(Tapedeck.REDIRECT_PATH)
            .query(null)
            .addQueryParameter(Tapedeck.REDIRECT_URL_PARAMETER, target.toString())
            .addQueryParameter(Tapedeck.REDIRECT_FROM_PARAMETER, from.host)
            .build()
        return mockResp.setHeader("Location", throughTapedeck.toString())
    }

    private fun forwardToNetwork(incoming: RecordedRequest): Forwarded {
        val start = System.nanoTime()
        return try {
            val contentType = incoming.headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.firstOrNull()?.toMediaTypeOrNull()
            val reqBody = incoming.body?.toRequestBody(contentType)
                // OkHttp requires a body for POST, PUT and PATCH, even an empty one
                ?: if (incoming.method.uppercase() in METHODS_WITH_BODY) ByteArray(0).toRequestBody(contentType) else null

            val reqBuilder = Request.Builder().url(incoming.url).method(incoming.method, reqBody)
            incoming.headers.forEach { (name, values) ->
                if (!isDroppedRequestHeader(name)) {
                    values.forEach { reqBuilder.addHeader(name, it) }
                }
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                // The forwarding client added Accept-Encoding itself (see DROPPED_REQUEST_HEADERS), so the body here is
                // already decoded: it's recorded and served decoded, without the encoding and length headers
                val headers = mutableMapOf<String, MutableList<String>>()
                resp.headers.forEach { (name, value) ->
                    if (!isDroppedResponseHeader(name)) {
                        headers.getOrPut(name) { mutableListOf() }.add(value)
                    }
                }
                val response = RecordedResponse(
                    code = resp.code,
                    message = resp.message,
                    headers = headers,
                    body = resp.body?.bytes()
                )
                Forwarded(response, null, (System.nanoTime() - start) / 1_000_000)
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed forwarding ${incoming.url} to real network", e)
            Forwarded(null, e, (System.nanoTime() - start) / 1_000_000)
        }
    }

    fun report(): TapedeckReport {
        return TapedeckReport(
            recordedCount = recordedCount.get(),
            replayedCount = replayedCount.get(),
            forwardedCount = forwardedCount.get(),
            unmatchedCount = unmatchedCount.get()
        )
    }

    companion object {
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")

        // Hop-by-hop headers (RFC 9110 7.6.1) and the ones OkHttp sets itself. Accept-Encoding in particular: forwarded,
        // it would stop the forwarding client from decoding gzip, and the encoded body would reach the app without its
        // Content-Encoding
        private val DROPPED_REQUEST_HEADERS = setOf(
            "host", "content-length", "accept-encoding", "connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "proxy-connection", "proxy-authorization", "proxy-authenticate"
        )

        // Bodies are served decoded, with MockWebServer setting the length
        private val DROPPED_RESPONSE_HEADERS = setOf(
            "content-length", "content-encoding", "transfer-encoding", "connection", "keep-alive", "te", "trailer",
            "upgrade", "proxy-connection", "proxy-authenticate"
        )

        internal fun isDroppedRequestHeader(name: String): Boolean =
            name.startsWith("X-Tapedeck-", ignoreCase = true) || name.lowercase() in DROPPED_REQUEST_HEADERS

        internal fun isDroppedResponseHeader(name: String): Boolean = name.lowercase() in DROPPED_RESPONSE_HEADERS

        internal fun RecordedResponse.toMockResponse(method: String): MockResponse {
            val mockResp = MockResponse().setStatus("HTTP/1.1 $code ${message.ifEmpty { "Tapedeck" }}")
            headers.forEach { (name, values) ->
                // Responses recorded by older versions, or loaded from HAR files, may still carry them
                if (!isDroppedResponseHeader(name)) {
                    values.forEach { mockResp.addHeader(name, it) }
                }
            }
            val body = body
            // A HEAD response has no body, and 204/304 can't have one
            if (body != null && !method.equals("HEAD", ignoreCase = true) && code != 204 && code != 304) {
                mockResp.setBody(Buffer().write(body))
            }
            return mockResp
        }
    }
}
