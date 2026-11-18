package io.github.tapedeck.macrobenchmark

import io.github.tapedeck.core.Tapedeck
import io.github.tapedeck.core.matcher.CompositeRequestMatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TapedeckDispatcherTest {

    private lateinit var backendServer: MockWebServer
    private lateinit var tapedeckMockServer: MockWebServer
    private lateinit var dispatcher: TapedeckDispatcher

    @Before
    fun setUp() {
        backendServer = MockWebServer()
        backendServer.start()

        dispatcher = TapedeckDispatcher(
            matcher = CompositeRequestMatcher(),
            forwardingClient = OkHttpClient.Builder().build(),
            mode = TapedeckMode.RECORD,
            latencyMs = 0L,
            recordServerErrors = false
        )

        tapedeckMockServer = MockWebServer()
        tapedeckMockServer.dispatcher = dispatcher
        tapedeckMockServer.start()
    }

    @After
    fun tearDown() {
        tapedeckMockServer.shutdown()
        backendServer.shutdown()
    }

    @Test
    fun `records in RECORD mode and replays in REPLAY mode without hitting backend`() {
        val targetUrl = backendServer.url("/v1/data").toString()
        backendServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"msg":"hello from real backend"}""")
        )

        val client = OkHttpClient()

        // 1. Warm-up RECORD call
        val recordRequest = Request.Builder()
            .url(tapedeckMockServer.url("/v1/data"))
            .header(Tapedeck.HEADER_ORIGINAL_URL, targetUrl)
            .build()

        val recordResp = client.newCall(recordRequest).execute()
        assertEquals(200, recordResp.code)
        assertEquals("""{"msg":"hello from real backend"}""", recordResp.body?.string())
        assertEquals(1, dispatcher.recordedCount.get())
        assertEquals(1, dispatcher.recording.size())

        // 2. Switch to REPLAY mode and shutdown backend to prove no network calls hit it
        dispatcher.mode = TapedeckMode.REPLAY
        backendServer.shutdown()

        // 3. Measured REPLAY call
        val replayRequest = Request.Builder()
            .url(tapedeckMockServer.url("/v1/data"))
            .header(Tapedeck.HEADER_ORIGINAL_URL, targetUrl)
            .build()

        val replayResp = client.newCall(replayRequest).execute()
        assertEquals(200, replayResp.code)
        assertEquals("""{"msg":"hello from real backend"}""", replayResp.body?.string())
        assertEquals(1, dispatcher.replayedCount.get())
    }

    @Test
    fun `does not record 5xx server errors when recordServerErrors is false`() {
        val targetUrl = backendServer.url("/v1/error").toString()
        backendServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("Service Unavailable")
        )

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(tapedeckMockServer.url("/v1/error"))
            .header(Tapedeck.HEADER_ORIGINAL_URL, targetUrl)
            .build()

        val resp = client.newCall(request).execute()
        assertEquals(503, resp.code)
        assertEquals(0, dispatcher.recordedCount.get())
        assertEquals(0, dispatcher.recording.size())
    }

    @Test
    fun `unmatched policy FAIL returns 500`() {
        val testDispatcher = TapedeckDispatcher(
            matcher = CompositeRequestMatcher(),
            mode = TapedeckMode.REPLAY,
            onUnmatched = OnUnmatched.FAIL
        )
        val server = MockWebServer()
        server.dispatcher = testDispatcher
        server.start()

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(server.url("/unknown"))
            .header(Tapedeck.HEADER_ORIGINAL_URL, "https://api.example.com/unknown")
            .build()

        val resp = client.newCall(request).execute()
        assertEquals(500, resp.code)
        assertEquals(1, testDispatcher.unmatchedCount.get())
        server.shutdown()
    }

    @Test
    fun `recordOnce runs block on first call and is noop on subsequent calls`() {
        val rule = ReplayRule(port = 29998)
        var callCount = 0

        rule.recordOnce(onComplete = {}) {
            callCount++
        }
        assertEquals(1, callCount)
        assertEquals(TapedeckMode.REPLAY, rule.server.mode)

        // Second call should be a no-op
        rule.recordOnce(onComplete = {}) {
            callCount++
        }
        assertEquals(1, callCount)
    }
}
