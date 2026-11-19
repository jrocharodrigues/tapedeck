package com.impecabel.tapedeck.macrobenchmark

import com.impecabel.tapedeck.core.Tapedeck
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
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The URL a replayed response carries, which clients resolve relative URLs against (a DASH or HLS player, a manifest's
 * segments), and requests that reach the server without going through ReplayInterceptor.
 */
class RealUrlTest {

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

    private fun tapedeck(followRedirects: Boolean = true): Pair<MockWebServer, TapedeckDispatcher> {
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

    // The backend as "localhost", which the app's ReplayInterceptor sends to Tapedeck; the server is on 127.0.0.1
    private fun localhostUrl(server: MockWebServer, path: String) =
        server.url(path).newBuilder().host("localhost").build()

    private fun appClient(tapedeck: MockWebServer) = OkHttpClient.Builder()
        .addInterceptor(ReplayInterceptor(hosts = setOf("localhost"), port = tapedeck.port))
        .build()

    @Test
    fun `a replayed response carries the request's real URL, recorded and replayed`() {
        val backend = server { MockResponse().setBody("<MPD/>") }
        val (tapedeck, dispatcher) = tapedeck()
        val url = localhostUrl(backend, "/sdash/LIVE/index.mpd")

        appClient(tapedeck).newCall(Request.Builder().url(url).build()).execute().use { recorded ->
            assertEquals(url, recorded.request.url)
            // What a player does with the manifest's relative URLs: they go to the backend's host, through Tapedeck
            assertEquals(localhostUrl(backend, "/sdash/LIVE/segment-1.m4s"), recorded.request.url.resolve("segment-1.m4s"))
        }

        dispatcher.mode = TapedeckMode.REPLAY
        appClient(tapedeck).newCall(Request.Builder().url(url).build()).execute().use { replayed ->
            assertEquals("<MPD/>", replayed.body!!.string())
            assertEquals(url, replayed.request.url)
        }
    }

    @Test
    fun `with followRedirects = false, the response carries the last redirect's target`() {
        val backend = server { request ->
            when (request.path) {
                "/a" -> MockResponse().setResponseCode(302).setHeader("Location", "/b/index.mpd")
                else -> MockResponse().setBody("final ${request.path}")
            }
        }
        val (tapedeck, _) = tapedeck(followRedirects = false)

        appClient(tapedeck).newCall(Request.Builder().url(localhostUrl(backend, "/a")).build()).execute().use {
            assertEquals("final /b/index.mpd", it.body!!.string())
            assertEquals(localhostUrl(backend, "/b/index.mpd"), it.request.url)
        }
    }

    @Test
    fun `a request sent to the server directly is answered with 502, not forwarded to the server itself`() {
        val (tapedeck, dispatcher) = tapedeck()
        dispatcher.mode = TapedeckMode.REPLAY
        // Before the fix: forwarded to its own URL, which came back as another direct request, endlessly
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

        client.newCall(Request.Builder().url(tapedeck.url("/sdash/LIVE/index.mpd")).build()).execute().use {
            assertEquals(502, it.code)
            assertNotNull(it.header(Tapedeck.HEADER_FORWARD_FAILED))
        }
        assertEquals(1, tapedeck.requestCount)
        assertEquals(0, dispatcher.forwardedCount.get())
        assertEquals(1, dispatcher.unmatchedCount.get())
    }
}
