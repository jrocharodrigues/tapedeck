package com.impecabel.tapedeck.macrobenchmark

import com.impecabel.tapedeck.core.Tapedeck
import com.impecabel.tapedeck.core.matcher.CompositeRequestMatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit

/**
 * Tapedeck's server on its own: requests are sent to it the way `ReplayInterceptor` sends them (with the original URL
 * in [Tapedeck.HEADER_ORIGINAL_URL]), and it forwards them to [backend]. End to end through the interceptor, see
 * [RedirectAndUrlTest].
 */
class TapedeckDispatcherTest {

    private lateinit var backend: MockWebServer
    private lateinit var tapedeck: MockWebServer
    private lateinit var dispatcher: TapedeckDispatcher

    // Like an app's client: OkHttp adds Accept-Encoding: gzip by itself
    private val appClient = OkHttpClient()

    @Before
    fun setUp() {
        backend = MockWebServer().apply { start() }
        dispatcher = TapedeckDispatcher(matcher = CompositeRequestMatcher(), latencyMs = 0L)
        tapedeck = serve(dispatcher)
    }

    @After
    fun tearDown() {
        tapedeck.shutdown()
        backend.shutdown()
    }

    private fun serve(dispatcher: TapedeckDispatcher) = MockWebServer().apply {
        this.dispatcher = dispatcher
        start()
    }

    private fun request(path: String, method: String = "GET", server: MockWebServer = tapedeck) = Request.Builder()
        .url(server.url(path))
        .method(method, null)
        .header(Tapedeck.HEADER_ORIGINAL_URL, backend.url(path).toString())

    private fun call(path: String, method: String = "GET") = appClient.newCall(request(path, method).build()).execute()

    private fun gzip(text: String): Buffer {
        val out = Buffer()
        GzipSink(out).buffer().use { it.writeUtf8(text) }
        return out
    }

    // Recording and replaying

    @Test
    fun `records in RECORD mode and replays in REPLAY mode without hitting the backend`() {
        backend.enqueue(MockResponse().setBody("""{"msg":"hello from real backend"}"""))

        call("/v1/data").use {
            assertEquals(200, it.code)
            assertEquals("""{"msg":"hello from real backend"}""", it.body!!.string())
        }
        assertEquals(1, dispatcher.recordedCount.get())
        assertEquals(1, dispatcher.recording.size())

        // The backend is down: only a replay can answer
        dispatcher.mode = TapedeckMode.REPLAY
        backend.shutdown()
        call("/v1/data").use {
            assertEquals(200, it.code)
            assertEquals("""{"msg":"hello from real backend"}""", it.body!!.string())
        }
        assertEquals(1, dispatcher.replayedCount.get())
    }

    @Test
    fun `5xx responses aren't recorded unless recordServerErrors`() {
        backend.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        call("/v1/error").use { assertEquals(503, it.code) }
        assertEquals(0, dispatcher.recordedCount.get())
        assertEquals(0, dispatcher.recording.size())
    }

    @Test
    fun `gzip responses are recorded and replayed decoded`() {
        backend.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .addHeader("Content-Encoding", "gzip")
                .setBody(gzip("""{"channels":[1,2,3]}"""))
        )

        call("/channels").use { resp ->
            assertEquals("""{"channels":[1,2,3]}""", resp.body!!.string())
            assertNull(resp.header("Content-Encoding"))
        }
        // The forwarding client asked for gzip itself, not because the app's header was copied
        assertEquals("gzip", backend.takeRequest().getHeader("Accept-Encoding"))
        val recorded = dispatcher.recording.entries.single().response
        assertEquals("""{"channels":[1,2,3]}""", String(recorded.body!!))
        assertFalse(recorded.headers.keys.any { it.equals("Content-Encoding", ignoreCase = true) })

        dispatcher.mode = TapedeckMode.REPLAY
        call("/channels").use { resp ->
            assertEquals("""{"channels":[1,2,3]}""", resp.body!!.string())
            assertEquals("application/json", resp.header("Content-Type"))
            assertNull(resp.header("Content-Encoding"))
        }
        assertEquals(1, dispatcher.replayedCount.get())
    }

    @Test
    fun `query variants replay their own responses`() {
        backend.enqueue(MockResponse().setBody("day 1"))
        backend.enqueue(MockResponse().setBody("day 2"))
        call("/epg?day=1").close()
        call("/epg?day=2").close()

        dispatcher.mode = TapedeckMode.REPLAY
        assertEquals("day 2", call("/epg?day=2").use { it.body!!.string() })
        assertEquals("day 1", call("/epg?day=1").use { it.body!!.string() })
        assertEquals("day 1", call("/epg?day=9").use { it.body!!.string() })
    }

    @Test
    fun `HEAD replays headers without a body`() {
        backend.enqueue(MockResponse().addHeader("ETag", "\"v1\""))
        call("/manifest", method = "HEAD").close()

        dispatcher.mode = TapedeckMode.REPLAY
        call("/manifest", method = "HEAD").use { resp ->
            assertEquals(200, resp.code)
            assertEquals("\"v1\"", resp.header("ETag"))
        }
        // The connection is still usable: a stray HEAD body would corrupt the next response
        backend.enqueue(MockResponse().setBody("after"))
        dispatcher.mode = TapedeckMode.RECORD
        assertEquals("after", call("/after").use { it.body!!.string() })
    }

    // Unmatched requests and forwarding failures

    @Test
    fun `unmatched policy FAIL returns 500`() {
        val failing = TapedeckDispatcher(
            matcher = CompositeRequestMatcher(),
            mode = TapedeckMode.REPLAY,
            latencyMs = 0L,
            onUnmatched = OnUnmatched.FAIL
        )
        val server = serve(failing)
        try {
            appClient.newCall(request("/unknown", server = server).build()).execute().use { assertEquals(500, it.code) }
            assertEquals(1, failing.unmatchedCount.get())
            assertEquals(0, backend.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an unreachable backend while recording is not recorded`() {
        backend.shutdown()

        call("/down").use {
            assertEquals(502, it.code)
            assertTrue(it.header(Tapedeck.HEADER_FORWARD_FAILED)!!.startsWith("ConnectException"))
        }
        assertEquals(0, dispatcher.recording.size())
        assertEquals(0, dispatcher.recordedCount.get())
    }

    private fun forwardFailureHeader(mode: TapedeckMode): String? {
        dispatcher.mode = mode
        backend.shutdown()
        return call("/down").use { assertEquals(502, it.code); it.header(Tapedeck.HEADER_FORWARD_FAILED) }
    }

    @Test
    fun `an unmatched request that can't be forwarded in replay carries the forward-failed marker`() {
        assertTrue(forwardFailureHeader(TapedeckMode.REPLAY)!!.startsWith("ConnectException"))
        assertEquals(1, dispatcher.unmatchedCount.get())
    }

    @Test
    fun `a request that can't be forwarded in passthrough carries the forward-failed marker`() {
        assertTrue(forwardFailureHeader(TapedeckMode.PASSTHROUGH)!!.startsWith("ConnectException"))
    }

    @Test
    fun `a backend 502 doesn't carry the forward-failed marker`() {
        backend.enqueue(MockResponse().setResponseCode(502).setBody("down"))

        call("/bad").use {
            assertEquals(502, it.code)
            assertNull(it.header(Tapedeck.HEADER_FORWARD_FAILED))
        }
    }

    @Test
    fun `the forward failure reason is a short printable header value`() {
        val reason = TapedeckDispatcher.forwardFailureReason(
            UnknownServiceException("CLEARTEXT communication to hôst\n not permitted " + "x".repeat(300))
        )
        assertTrue(reason.startsWith("UnknownServiceException: CLEARTEXT communication to h?st"))
        assertTrue(reason.all { it in ' '..'~' })
        assertEquals(200, reason.length)
        assertEquals("unknown", TapedeckDispatcher.forwardFailureReason(null))
    }

    @Test
    fun `a request sent to the server directly is answered with 502, not forwarded to the server itself`() {
        dispatcher.mode = TapedeckMode.REPLAY
        // Before 0.2.3: forwarded to its own URL, which came back as another direct request, endlessly
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

        client.newCall(Request.Builder().url(tapedeck.url("/sdash/LIVE/index.mpd")).build()).execute().use {
            assertEquals(502, it.code)
            assertNotNull(it.header(Tapedeck.HEADER_FORWARD_FAILED))
        }
        assertEquals(1, tapedeck.requestCount)
        assertEquals(0, dispatcher.forwardedCount.get())
        assertEquals(1, dispatcher.unmatchedCount.get())
    }

    // Cache-Control, which ReplayInterceptor(bypassHttpCache = true) replaces (see BypassHttpCacheTest in tapedeck-okhttp)

    private fun forwardWithHeaders(vararg headers: Pair<String, String>): RecordedRequest {
        backend.enqueue(MockResponse().setBody("ok"))
        val request = request("/cc").apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        appClient.newCall(request).execute().close()
        return backend.takeRequest()
    }

    @Test
    fun `bypassed cache headers are restored before forwarding`() {
        val withoutOwn = forwardWithHeaders(
            "Cache-Control" to "no-cache, no-store",
            Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL to "",
        )
        assertNull(withoutOwn.getHeader("Cache-Control"))
        assertNull(withoutOwn.getHeader(Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL))

        val withOwn = forwardWithHeaders(
            "Cache-Control" to "no-cache, no-store",
            Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL to "max-age=0",
        )
        assertEquals("max-age=0", withOwn.getHeader("Cache-Control"))
        assertEquals("max-age=0", dispatcher.recording.entries.last().request.headers.entries
            .first { it.key.equals("Cache-Control", ignoreCase = true) }.value.single())
    }

    @Test
    fun `Cache-Control is forwarded unchanged when the cache wasn't bypassed`() {
        assertEquals("max-age=0", forwardWithHeaders("Cache-Control" to "max-age=0").getHeader("Cache-Control"))
    }
}
