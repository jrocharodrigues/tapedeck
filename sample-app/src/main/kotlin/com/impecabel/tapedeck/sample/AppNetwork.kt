package com.impecabel.tapedeck.sample

import com.impecabel.tapedeck.okhttp.ReplayInterceptor
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
