package io.github.tapedeck.sample

import io.github.tapedeck.okhttp.ReplayInterceptor
import okhttp3.OkHttpClient

object AppNetwork {
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(
                ReplayInterceptor(
                    hosts = setOf("api.github.com", "httpbin.org")
                )
            )
            .build()
    }
}
