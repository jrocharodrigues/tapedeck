package com.impecabel.tapedeck.macrobenchmark

import com.impecabel.tapedeck.core.Tapedeck
import com.impecabel.tapedeck.core.matcher.CompositeRequestMatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.net.ConnectException
import java.net.Socket

class TapedeckDispatcherFixesTest {

    private lateinit var backend: MockWebServer
    private lateinit var tapedeck: MockWebServer
    private lateinit var dispatcher: TapedeckDispatcher

    // Like an app's client: OkHttp adds Accept-Encoding: gzip by itself
    private val appClient = OkHttpClient()

    @Before
    fun setUp() {
        backend = MockWebServer().apply { start() }
        dispatcher = TapedeckDispatcher(matcher = CompositeRequestMatcher(), latencyMs = 0L)
        tapedeck = MockWebServer().apply {
            dispatcher = this@TapedeckDispatcherFixesTest.dispatcher
            start()
        }
    }

    @After
    fun tearDown() {
        tapedeck.shutdown()
        backend.shutdown()
    }

    private fun call(path: String, method: String = "GET") = appClient.newCall(
        Request.Builder()
            .url(tapedeck.url(path))
            .method(method, null)
            .header(Tapedeck.HEADER_ORIGINAL_URL, backend.url(path).toString())
            .build()
    ).execute()

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun gzip(text: String): Buffer {
        val out = Buffer()
        GzipSink(out).buffer().use { it.writeUtf8(text) }
        return out
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

    @Test
    fun `an unreachable backend while recording is not recorded`() {
        val url = backend.url("/down")
        backend.shutdown()

        val resp = appClient.newCall(
            Request.Builder().url(tapedeck.url("/down")).header(Tapedeck.HEADER_ORIGINAL_URL, url.toString()).build()
        ).execute()

        assertEquals(502, resp.code)
        assertTrue(resp.header(Tapedeck.HEADER_FORWARD_FAILED)!!.startsWith("ConnectException"))
        resp.close()
        assertEquals(0, dispatcher.recording.size())
        assertEquals(0, dispatcher.recordedCount.get())
    }

    private fun forwardFailureHeader(mode: TapedeckMode): String? {
        dispatcher.mode = mode
        val url = backend.url("/down")
        backend.shutdown()
        return appClient.newCall(
            Request.Builder().url(tapedeck.url("/down")).header(Tapedeck.HEADER_ORIGINAL_URL, url.toString()).build()
        ).execute().use { assertEquals(502, it.code); it.header(Tapedeck.HEADER_FORWARD_FAILED) }
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
            java.net.UnknownServiceException("CLEARTEXT communication to hôst\n not permitted " + "x".repeat(300))
        )
        assertTrue(reason.startsWith("UnknownServiceException: CLEARTEXT communication to h?st"))
        assertTrue(reason.all { it in ' '..'~' })
        assertEquals(200, reason.length)
        assertEquals("unknown", TapedeckDispatcher.forwardFailureReason(null))
    }

    @Test
    fun `a disabled rule neither starts the server nor records`() {
        val port = freePort()
        val rule = ReplayRule(port = port, enabled = false)
        var ran = false
        var recorded = false

        rule.apply(object : Statement() {
            override fun evaluate() {
                ran = true
                assertTrue(runCatching { Socket("127.0.0.1", port).close() }.exceptionOrNull() is ConnectException)
                rule.recordOnce(onComplete = {}) { recorded = true }
            }
        }, Description.EMPTY).evaluate()

        assertTrue(ran)
        assertFalse(recorded)
    }

    @Test
    fun `an explicit port is served only while the test runs`() {
        val port = freePort()
        val rule = ReplayRule(port = port)
        var listening = false

        rule.apply(object : Statement() {
            override fun evaluate() {
                listening = runCatching { Socket("127.0.0.1", port).close() }.isSuccess
            }
        }, Description.EMPTY).evaluate()

        assertTrue(listening)
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.exceptionOrNull() is ConnectException)
    }

    private fun forwardWithCacheHeaders(vararg headers: Pair<String, String>): okhttp3.mockwebserver.RecordedRequest {
        backend.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder()
            .url(tapedeck.url("/cc"))
            .header(Tapedeck.HEADER_ORIGINAL_URL, backend.url("/cc").toString())
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        appClient.newCall(request).execute().close()
        return backend.takeRequest()
    }

    @Test
    fun `bypassed cache headers are restored before forwarding`() {
        val withoutOwn = forwardWithCacheHeaders(
            "Cache-Control" to "no-cache, no-store",
            Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL to "",
        )
        assertNull(withoutOwn.getHeader("Cache-Control"))
        assertNull(withoutOwn.getHeader(Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL))

        val withOwn = forwardWithCacheHeaders(
            "Cache-Control" to "no-cache, no-store",
            Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL to "max-age=0",
        )
        assertEquals("max-age=0", withOwn.getHeader("Cache-Control"))
        assertEquals("max-age=0", dispatcher.recording.entries.last().request.headers.entries
            .first { it.key.equals("Cache-Control", ignoreCase = true) }.value.single())
    }

    @Test
    fun `Cache-Control is forwarded unchanged when the cache wasn't bypassed`() {
        assertEquals("max-age=0", forwardWithCacheHeaders("Cache-Control" to "max-age=0").getHeader("Cache-Control"))
    }
}
