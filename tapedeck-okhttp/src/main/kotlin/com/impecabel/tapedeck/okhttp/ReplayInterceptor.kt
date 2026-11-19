package com.impecabel.tapedeck.okhttp

import com.impecabel.tapedeck.core.Tapedeck
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.ConnectException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Sends requests to [hosts] to Tapedeck's server on `127.0.0.1` (run by the benchmark's `ReplayRule`), which records or
 * replays them. Add it as an application interceptor, before any interceptor that depends on the request's host or
 * scheme.
 *
 * @param hosts host names to send to Tapedeck, compared ignoring case. `*.example.com` matches any subdomain of
 *   example.com (`api.example.com`, `a.b.example.com`), but not example.com itself (list it too if needed) nor
 *   `badexample.com`. `*` matches every host except loopback ones (`localhost`, `127.0.0.0/8`, `::1`), which are never
 *   sent to Tapedeck through it (e.g. a local video proxy); list a loopback host explicitly to send it anyway.
 * @param port the server's port: [Tapedeck.DEFAULT_PORT] unless `ReplayRule` is given another one, which must be the
 *   same here.
 * @param fallbackToNetworkOnFailure sends the request to the network itself (with the app's own network stack: its
 *   cleartext policy, proxy, pinning, DNS) instead of failing it when the server isn't running (the app opened outside
 *   a benchmark, or the rule disabled: the connection to 127.0.0.1 is refused at once), or when the server got no
 *   response from the backend (its 502 carries [Tapedeck.HEADER_FORWARD_FAILED]). Backend responses, 5xx included,
 *   are returned as they are.
 * @param forwardPaths paths that always go straight to the network, even on [hosts]: a request matches when its
 *   (encoded) path equals one of them or continues it with `/`. For requests that must never be recorded or replayed and
 *   shouldn't pay the extra hop, e.g. streaming session control.
 * @param alwaysForward any other requests that always go straight to the network.
 * @param bypassHttpCache keeps the requests sent to Tapedeck out of the client's HTTP cache (OkHttp's `Cache`): they're
 *   neither answered from it nor stored in it, whatever cache headers the responses carry, and the backend still sees
 *   the request's own `Cache-Control`. With `false` (the default), replayed responses are cached like real ones, so a
 *   benchmark includes the cost of the cache. Requests sent to the network directly (forwarded, or the fallback) always
 *   use the cache.
 */
class ReplayInterceptor(
    val hosts: Set<String>,
    val port: Int = Tapedeck.DEFAULT_PORT,
    val fallbackToNetworkOnFailure: Boolean = true,
    enabled: Boolean = true,
    val forwardPaths: Set<String> = emptySet(),
    val alwaysForward: (Request) -> Boolean = { false },
    val bypassHttpCache: Boolean = false
) : Interceptor {

    @Volatile
    var enabled: Boolean = enabled

    private val logger = Logger.getLogger("Tapedeck")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!enabled || hosts.isEmpty()) {
            return chain.proceed(request)
        }

        if (!isHostAllowed(request.url.host) || isForwardedPath(request.url.encodedPath) || alwaysForward(request)) {
            return chain.proceed(request)
        }

        if (!fallbackToNetworkOnFailure) {
            return withRealUrl(chain.proceed(rewrite(request, port)), request)
        }

        val response = try {
            withRealUrl(chain.proceed(rewrite(request, port)), request)
        } catch (e: ConnectException) {
            logger.log(
                Level.WARNING,
                "Tapedeck server not reachable at 127.0.0.1:$port. Falling back to real network for ${request.url}."
            )
            return chain.proceed(request)
        }

        val forwardFailure = response.header(Tapedeck.HEADER_FORWARD_FAILED) ?: return response
        response.close()
        logger.log(
            Level.WARNING,
            "Tapedeck couldn't forward ${request.url} ($forwardFailure). Sending it to the real network directly."
        )
        return chain.proceed(request)
    }

    /**
     * [response], served by Tapedeck's server, with the URL it answers in `response.request`: the request's own, or with
     * `followRedirects = false` the last redirect's target, instead of the server's 127.0.0.1 one. Clients read it as
     * the response's final URL, e.g. Media3's `OkHttpDataSource.getUri()`, against which a DASH or HLS player resolves the
     * manifest's relative URLs: with the server's, those requests would go to the server directly, without the
     * original URL.
     */
    private fun withRealUrl(response: Response, request: Request): Response {
        val served = response.request.url
        val realUrl = served.takeIf { it.encodedPath == Tapedeck.REDIRECT_PATH }
            ?.queryParameter(Tapedeck.REDIRECT_URL_PARAMETER)
            ?.toHttpUrlOrNull()
            ?: request.url
        return response.newBuilder().request(request.newBuilder().url(realUrl).build()).build()
    }

    private fun rewrite(request: Request, serverPort: Int): Request {
        val rewrittenUrl = request.url.newBuilder()
            .scheme("http")
            .host("127.0.0.1")
            .port(serverPort)
            .build()

        val builder = request.newBuilder()
            .url(rewrittenUrl)
            .header(Tapedeck.HEADER_ORIGINAL_URL, request.url.toString())
            .header(Tapedeck.HEADER_ORIGINAL_SCHEME, request.url.scheme)
            .header(Tapedeck.HEADER_ORIGINAL_HOST, request.url.host)
        if (bypassHttpCache) {
            // OkHttp's cache neither serves a no-cache request nor stores the response to a no-store one, even when a
            // network interceptor rewrites the response's cache headers
            builder
                .header(Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL, request.headers.values("Cache-Control").joinToString(", "))
                .header("Cache-Control", NO_CACHE)
        }
        return builder.build()
    }

    private fun isHostAllowed(host: String): Boolean = hosts.any { allowed -> hostMatches(allowed, host) }

    private fun isForwardedPath(path: String): Boolean = forwardPaths.any { forwarded ->
        val prefix = forwarded.trimEnd('/')
        path == prefix || path.startsWith("$prefix/") || (prefix.isEmpty() && path.startsWith("/"))
    }

    internal companion object {
        private const val NO_CACHE = "no-cache, no-store"

        fun hostMatches(allowed: String, host: String): Boolean {
            if (allowed == "*") return !isLoopback(host)
            if (allowed.startsWith("*.")) {
                // The dot keeps "*.example.com" from matching "badexample.com"
                return host.endsWith(allowed.substring(1), ignoreCase = true)
            }
            return host.equals(allowed, ignoreCase = true)
        }

        /** `localhost` (and its subdomains), `127.0.0.0/8` and `::1`, as OkHttp gives a URL's host (IPv6 canonical). */
        fun isLoopback(host: String): Boolean {
            val name = host.lowercase().removeSuffix(".")
            if (name == "localhost" || name.endsWith(".localhost")) return true
            if (name == "::1" || name == "[::1]") return true
            val octets = name.split('.')
            return octets.size == 4 && octets[0] == "127" && octets.all { it.toIntOrNull() in 0..255 }
        }
    }
}
