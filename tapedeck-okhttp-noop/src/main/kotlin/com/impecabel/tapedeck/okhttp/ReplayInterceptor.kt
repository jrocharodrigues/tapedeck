package com.impecabel.tapedeck.okhttp

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * No-op implementation of ReplayInterceptor for release builds: same constructor as tapedeck-okhttp's, so either can be
 * swapped in by build type, and every request goes straight to the network.
 */
class ReplayInterceptor(
    val hosts: Set<String> = emptySet(),
    val port: Int = 28765,
    val fallbackToNetworkOnFailure: Boolean = true,
    enabled: Boolean = false,
    val forwardPaths: Set<String> = emptySet(),
    val alwaysForward: (Request) -> Boolean = { false }
) : Interceptor {

    // Accepted for API compatibility; never routes anything
    @Volatile
    var enabled: Boolean = enabled

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}
