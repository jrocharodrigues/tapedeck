package com.impecabel.tapedeck.macrobenchmark

import com.impecabel.tapedeck.core.matcher.CompositeRequestMatcher
import com.impecabel.tapedeck.okhttp.ReplayInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Redirects end to end: an app's OkHttp client with ReplayInterceptor, Tapedeck's server and a backend. */
class RedirectTest {

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun server(dispatch: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = dispatch(request)
        }
        start()
        servers += this
    }

    private fun tapedeck(followRedirects: Boolean): Pair<MockWebServer, TapedeckDispatcher> {
        val dispatcher = TapedeckDispatcher(
            matcher = CompositeRequestMatcher(),
            latencyMs = 0L,
            followRedirects = followRedirects
        )
        val server = MockWebServer().apply {
            this.dispatcher = dispatcher
            start()
            servers += this
        }
        return server to dispatcher
    }

    // The app's client: replays "localhost", follows redirects itself (OkHttp's default)
    private fun appGet(tapedeck: MockWebServer, url: String, authorization: String? = null): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(ReplayInterceptor(hosts = setOf("localhost"), port = tapedeck.port))
            .build()
        val request = Request.Builder().url(url).apply { authorization?.let { header("Authorization", it) } }.build()
        return client.newCall(request).execute().use { it.body!!.string() }
    }

    private fun localhostUrl(server: MockWebServer, path: String) =
        server.url(path).newBuilder().host("localhost").build().toString()

    @Test
    fun `followed redirects record and replay only the final response`() {
        val backend = server { request ->
            when (request.path) {
                "/a" -> MockResponse().setResponseCode(302).setHeader("Location", "/b")
                else -> MockResponse().setBody("final")
            }
        }
        val (tapedeck, dispatcher) = tapedeck(followRedirects = true)
        val url = localhostUrl(backend, "/a")

        assertEquals("final", appGet(tapedeck, url))
        val entry = dispatcher.recording.entries.single()
        assertEquals(url, entry.request.url)
        assertEquals(200, entry.response.code)

        dispatcher.mode = TapedeckMode.REPLAY
        backend.shutdown()
        assertEquals("final", appGet(tapedeck, url))
        assertEquals(1, dispatcher.replayedCount.get())
    }

    @Test
    fun `unfollowed redirects are recorded and replayed hop by hop through Tapedeck`() {
        val backend = server { request ->
            when (request.path) {
                "/a" -> MockResponse().setResponseCode(302).setHeader("Location", "/b?x=1")
                else -> MockResponse().setBody("final ${request.path}")
            }
        }
        val (tapedeck, dispatcher) = tapedeck(followRedirects = false)
        val url = localhostUrl(backend, "/a")
        val warnings = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level == Level.WARNING && "redirect" in record.message) warnings += record.message
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        Logger.getLogger("TapedeckDispatcher").addHandler(handler)
        try {
            assertEquals("final /b?x=1", appGet(tapedeck, url))
            assertEquals("final /b?x=1", appGet(tapedeck, url))
        } finally {
            Logger.getLogger("TapedeckDispatcher").removeHandler(handler)
        }

        // Both hops reached the backend through Tapedeck and were recorded, the 3xx with its original Location
        assertEquals(4, backend.requestCount)
        val (redirect, final) = dispatcher.recording.entries.take(2)
        assertEquals(302, redirect.response.code)
        assertEquals(listOf("/b?x=1"), redirect.response.headers["location"] ?: redirect.response.headers["Location"])
        assertEquals(localhostUrl(backend, "/b?x=1"), final.request.url)
        assertEquals(1, warnings.size)

        dispatcher.mode = TapedeckMode.REPLAY
        backend.shutdown()
        assertEquals("final /b?x=1", appGet(tapedeck, url))
        assertEquals(2, dispatcher.replayedCount.get())
        assertEquals(0, dispatcher.unmatchedCount.get())
    }

    @Test
    fun `cross-host unfollowed redirects drop credentials like OkHttp does`() {
        val other = server { request -> MockResponse().setBody("other ${request.getHeader("Authorization")}") }
        val backend = server {
            MockResponse().setResponseCode(301).setHeader("Location", other.url("/landing").toString())
        }
        val (tapedeck, dispatcher) = tapedeck(followRedirects = false)
        val url = localhostUrl(backend, "/a")

        assertEquals("other null", appGet(tapedeck, url, authorization = "Bearer secret"))
        assertEquals("Bearer secret", backend.takeRequest().getHeader("Authorization"))
        assertNull(other.takeRequest().getHeader("Authorization"))

        dispatcher.mode = TapedeckMode.REPLAY
        backend.shutdown()
        other.shutdown()
        assertEquals("other null", appGet(tapedeck, url, authorization = "Bearer secret"))
        assertTrue(dispatcher.replayedCount.get() == 2)
    }
}
