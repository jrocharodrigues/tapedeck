package com.impecabel.tapedeck.okhttp

import com.impecabel.tapedeck.core.Tapedeck
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
 *   `badexample.com`.
 * @param port the server's port: [Tapedeck.DEFAULT_PORT] unless `ReplayRule` is given another one, which must be the
 *   same here.
 * @param fallbackToNetworkOnFailure when the server isn't running (the app opened outside a benchmark, or the rule
 *   disabled), the connection to 127.0.0.1 is refused at once, and the request goes to the network instead of failing.
 * @param forwardPaths paths that always go straight to the network, even on [hosts]: a request matches when its
 *   (encoded) path equals one of them or continues it with `/`. For requests that must never be recorded or replayed and
 *   shouldn't pay the extra hop, e.g. streaming session control.
 * @param alwaysForward any other requests that always go straight to the network.
 */
class ReplayInterceptor(
    val hosts: Set<String>,
    val port: Int = Tapedeck.DEFAULT_PORT,
    val fallbackToNetworkOnFailure: Boolean = true,
    enabled: Boolean = true,
    val forwardPaths: Set<String> = emptySet(),
    val alwaysForward: (Request) -> Boolean = { false }
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

        return if (fallbackToNetworkOnFailure) {
            try {
                chain.proceed(rewrite(request, port))
            } catch (e: ConnectException) {
                logger.log(
                    Level.WARNING,
                    "Tapedeck server not reachable at 127.0.0.1:$port. Falling back to real network for ${request.url}."
                )
                chain.proceed(request)
            }
        } else {
            chain.proceed(rewrite(request, port))
        }
    }

    private fun rewrite(request: Request, serverPort: Int): Request {
        val rewrittenUrl = request.url.newBuilder()
            .scheme("http")
            .host("127.0.0.1")
            .port(serverPort)
            .build()

        return request.newBuilder()
            .url(rewrittenUrl)
            .header(Tapedeck.HEADER_ORIGINAL_URL, request.url.toString())
            .header(Tapedeck.HEADER_ORIGINAL_SCHEME, request.url.scheme)
            .header(Tapedeck.HEADER_ORIGINAL_HOST, request.url.host)
            .build()
    }

    private fun isHostAllowed(host: String): Boolean = hosts.any { allowed -> hostMatches(allowed, host) }

    private fun isForwardedPath(path: String): Boolean = forwardPaths.any { forwarded ->
        val prefix = forwarded.trimEnd('/')
        path == prefix || path.startsWith("$prefix/") || (prefix.isEmpty() && path.startsWith("/"))
    }

    internal companion object {
        fun hostMatches(allowed: String, host: String): Boolean {
            if (allowed.startsWith("*.")) {
                // The dot keeps "*.example.com" from matching "badexample.com"
                return host.endsWith(allowed.substring(1), ignoreCase = true)
            }
            return host.equals(allowed, ignoreCase = true)
        }
    }
}
