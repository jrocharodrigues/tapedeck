package io.github.tapedeck.macrobenchmark

import io.github.tapedeck.core.Tapedeck
import io.github.tapedeck.core.matcher.RequestMatcher
import io.github.tapedeck.core.model.RecordedExchange
import io.github.tapedeck.core.model.RecordedRequest
import io.github.tapedeck.core.model.RecordedResponse
import io.github.tapedeck.core.model.Recording
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

class TapedeckDispatcher(
    val recording: Recording = Recording(),
    val matcher: RequestMatcher,
    val forwardingClient: OkHttpClient = OkHttpClient.Builder().build(),
    var mode: TapedeckMode = TapedeckMode.RECORD,
    val latencyMs: Long = 100L,
    val recordServerErrors: Boolean = false,
    val onUnmatched: OnUnmatched = OnUnmatched.FORWARD
) : Dispatcher() {

    private val logger = Logger.getLogger("TapedeckDispatcher")

    val recordedCount = AtomicInteger(0)
    val replayedCount = AtomicInteger(0)
    val forwardedCount = AtomicInteger(0)
    val unmatchedCount = AtomicInteger(0)

    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
        val originalUrl = request.getHeader(Tapedeck.HEADER_ORIGINAL_URL)
            ?: request.requestUrl?.toString()
            ?: "http://127.0.0.1"

        val bodyBytes = request.body.readByteArray()
        val incoming = RecordedRequest(
            method = request.method ?: "GET",
            url = originalUrl,
            headers = request.headers.toMultimap(),
            body = if (bodyBytes.isNotEmpty()) bodyBytes else null
        )

        return when (mode) {
            TapedeckMode.RECORD -> handleRecord(incoming)
            TapedeckMode.REPLAY -> handleReplay(incoming)
            TapedeckMode.PASSTHROUGH -> forwardToNetwork(incoming)
        }
    }

    private fun handleRecord(incoming: RecordedRequest): MockResponse {
        val forwardResp = forwardToNetwork(incoming)
        val statusCode = extractStatusCode(forwardResp)

        val isServerError = statusCode in 500..599
        if (isServerError && !recordServerErrors) {
            logger.log(Level.WARNING, "Tapedeck: Server error $statusCode for ${incoming.url} not recorded.")
            return forwardResp
        }

        val exchange = RecordedExchange(
            request = incoming,
            response = RecordedResponse(
                code = statusCode,
                headers = extractHeaders(forwardResp),
                body = extractBodyBytes(forwardResp)
            )
        )
        recording.add(exchange)
        recordedCount.incrementAndGet()
        return forwardResp
    }

    private fun handleReplay(incoming: RecordedRequest): MockResponse {
        val matched = recording.find(incoming, matcher)

        if (matched != null) {
            replayedCount.incrementAndGet()
            val mockResp = MockResponse()
                .setResponseCode(matched.response.code)

            if (latencyMs > 0) {
                mockResp.setHeadersDelay(latencyMs, TimeUnit.MILLISECONDS)
            }

            matched.response.headers.forEach { (name, values) ->
                if (!name.equals("Content-Encoding", ignoreCase = true) &&
                    !name.equals("Transfer-Encoding", ignoreCase = true) &&
                    !name.equals("Content-Length", ignoreCase = true)
                ) {
                    values.forEach { mockResp.addHeader(name, it) }
                }
            }

            val body = matched.response.body
            if (body != null) {
                val buffer = Buffer().write(body)
                mockResp.setBody(buffer)
            }
            return mockResp
        }

        unmatchedCount.incrementAndGet()
        logger.log(Level.WARNING, "Tapedeck: Unmatched request for ${incoming.url} in REPLAY mode.")

        return when (onUnmatched) {
            OnUnmatched.FORWARD -> {
                forwardedCount.incrementAndGet()
                forwardToNetwork(incoming)
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

    private fun forwardToNetwork(incoming: RecordedRequest): MockResponse {
        val reqBuilder = Request.Builder().url(incoming.url)

        val contentType = incoming.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()?.toMediaTypeOrNull()

        val reqBody = incoming.body?.toRequestBody(contentType)

        reqBuilder.method(incoming.method, reqBody)

        incoming.headers.forEach { (name, values) ->
            if (!name.startsWith("X-Tapedeck-", ignoreCase = true) &&
                !name.equals("Host", ignoreCase = true) &&
                !name.equals("Content-Length", ignoreCase = true)
            ) {
                values.forEach { reqBuilder.addHeader(name, it) }
            }
        }

        return try {
            forwardingClient.newCall(reqBuilder.build()).execute().use { resp ->
                val mockResp = MockResponse().setResponseCode(resp.code)
                resp.headers.forEach { (name, value) ->
                    if (!name.equals("Content-Encoding", ignoreCase = true) &&
                        !name.equals("Transfer-Encoding", ignoreCase = true) &&
                        !name.equals("Content-Length", ignoreCase = true)
                    ) {
                        mockResp.addHeader(name, value)
                    }
                }
                val body = resp.body?.bytes()
                if (body != null) {
                    mockResp.setBody(Buffer().write(body))
                }
                mockResp
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed forwarding ${incoming.url} to real network", e)
            MockResponse()
                .setResponseCode(502)
                .setBody("Tapedeck Bad Gateway: ${e.message}")
        }
    }

    private fun extractStatusCode(mockResponse: MockResponse): Int {
        val statusLine = mockResponse.status
        return statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 200
    }

    private fun extractHeaders(mockResponse: MockResponse): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        mockResponse.headers.forEach { (name, value) ->
            result.getOrPut(name) { mutableListOf() }.add(value)
        }
        return result
    }

    private fun extractBodyBytes(mockResponse: MockResponse): ByteArray? {
        val buffer = mockResponse.getBody() ?: return null
        return buffer.clone().readByteArray()
    }

    fun report(): TapedeckReport {
        return TapedeckReport(
            recordedCount = recordedCount.get(),
            replayedCount = replayedCount.get(),
            forwardedCount = forwardedCount.get(),
            unmatchedCount = unmatchedCount.get()
        )
    }
}
