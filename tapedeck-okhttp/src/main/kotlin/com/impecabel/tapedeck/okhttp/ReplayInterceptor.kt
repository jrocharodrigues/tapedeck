package com.impecabel.tapedeck.okhttp

import com.impecabel.tapedeck.core.Tapedeck
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.util.logging.Level
import java.util.logging.Logger

class ReplayInterceptor(
    val hosts: Set<String>,
    val port: Int = Tapedeck.DEFAULT_PORT,
    val fallbackToNetworkOnFailure: Boolean = true,
    var enabled: Boolean = true
) : Interceptor {

    private val logger = Logger.getLogger("Tapedeck")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!enabled || hosts.isEmpty()) {
            return chain.proceed(request)
        }

        val requestHost = request.url.host
        val shouldIntercept = isHostAllowed(requestHost)

        if (!shouldIntercept) {
            return chain.proceed(request)
        }

        val rewrittenUrl = request.url.newBuilder()
            .scheme("http")
            .host("127.0.0.1")
            .port(port)
            .build()

        val rewrittenRequest = request.newBuilder()
            .url(rewrittenUrl)
            .header(Tapedeck.HEADER_ORIGINAL_URL, request.url.toString())
            .header(Tapedeck.HEADER_ORIGINAL_SCHEME, request.url.scheme)
            .header(Tapedeck.HEADER_ORIGINAL_HOST, request.url.host)
            .build()

        return if (fallbackToNetworkOnFailure) {
            try {
                chain.proceed(rewrittenRequest)
            } catch (e: ConnectException) {
                logger.log(
                    Level.WARNING,
                    "Tapedeck server not reachable at 127.0.0.1:$port. Falling back to real network for ${request.url}."
                )
                chain.proceed(request)
            }
        } else {
            chain.proceed(rewrittenRequest)
        }
    }

    private fun isHostAllowed(host: String): Boolean {
        if (hosts.contains(host)) return true
        return hosts.any { allowed ->
            if (allowed.startsWith("*.")) {
                val suffix = allowed.substring(2)
                host.endsWith(suffix, ignoreCase = true)
            } else {
                host.equals(allowed, ignoreCase = true)
            }
        }
    }
}
