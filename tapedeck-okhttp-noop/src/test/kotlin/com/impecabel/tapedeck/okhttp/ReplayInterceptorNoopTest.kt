package com.impecabel.tapedeck.okhttp

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Proxy

class ReplayInterceptorNoopTest {

    @Test
    fun `noop interceptor passes request directly`() {
        val interceptor = ReplayInterceptor(hosts = setOf("example.com"))

        val dummyRequest = Request.Builder().url("https://example.com/").build()
        val dummyResponse = Response.Builder()
            .request(dummyRequest)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()

        var proceedCalled = false
        val chain = Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java)
        ) { _, method, args ->
            when (method.name) {
                "request" -> dummyRequest
                "proceed" -> {
                    proceedCalled = true
                    dummyResponse
                }
                else -> null
            }
        } as Interceptor.Chain

        val result = interceptor.intercept(chain)
        assertNotNull(result)
        assert(proceedCalled)
    }
}

// Compiles only while the no-op accepts every constructor argument of tapedeck-okhttp's ReplayInterceptor
@Suppress("unused")
private val sameConstructorAsTheRealInterceptor = ReplayInterceptor(
    hosts = setOf("*.example.com"),
    port = 28765,
    fallbackToNetworkOnFailure = true,
    enabled = true,
    forwardPaths = setOf("/stream"),
    alwaysForward = { false }
)
