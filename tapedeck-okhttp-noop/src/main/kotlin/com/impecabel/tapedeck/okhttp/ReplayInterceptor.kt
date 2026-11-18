package com.impecabel.tapedeck.okhttp

import okhttp3.Interceptor
import okhttp3.Response

/**
 * No-op implementation of ReplayInterceptor for release builds.
 */
class ReplayInterceptor(
    val hosts: Set<String> = emptySet(),
    val port: Int = 28765,
    val fallbackToNetworkOnFailure: Boolean = true,
    var enabled: Boolean = false
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}
