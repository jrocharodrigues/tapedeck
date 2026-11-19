package com.impecabel.tapedeck.okhttp

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

/** Which requests go to Tapedeck's server and which straight to the network (both played by local servers). */
class ReplayInterceptorRoutingTest {

    private lateinit var backend: MockWebServer
    private lateinit var tapedeck: MockWebServer

    @Before
    fun setUp() {
        backend = MockWebServer().apply {
            dispatcher = constant("backend")
            start()
        }
        tapedeck = MockWebServer().apply {
            dispatcher = constant("tapedeck")
            start()
        }
    }

    @After
    fun tearDown() {
        backend.shutdown()
        tapedeck.shutdown()
    }

    private fun constant(body: String) = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setBody(body)
    }

    // The backend is reached as "localhost", so the interceptor sees that host name
    private fun get(interceptor: ReplayInterceptor, path: String): String {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val url = backend.url(path).newBuilder().host("localhost").build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
    }

    @Test
    fun `wildcard hosts match subdomains at a dot boundary only`() {
        assertTrue(ReplayInterceptor.hostMatches("*.example.com", "api.example.com"))
        assertTrue(ReplayInterceptor.hostMatches("*.example.com", "a.b.EXAMPLE.com"))
        assertFalse(ReplayInterceptor.hostMatches("*.example.com", "badexample.com"))
        assertFalse(ReplayInterceptor.hostMatches("*.example.com", "example.com"))
        assertTrue(ReplayInterceptor.hostMatches("Example.com", "example.COM"))
        assertFalse(ReplayInterceptor.hostMatches("example.com", "api.example.com"))
    }

    @Test
    fun `star matches every host but loopback ones`() {
        listOf("api.example.com", "cdn-er-images.online.meo.pt", "example", "10.0.0.1", "2001:db8::1", "127.example.com")
            .forEach { assertTrue(it, ReplayInterceptor.hostMatches("*", it)) }
        listOf("127.0.0.1", "127.1.2.3", "localhost", "LOCALHOST", "a.localhost", "::1")
            .forEach { assertFalse(it, ReplayInterceptor.hostMatches("*", it)) }
        // Other patterns are unchanged: an explicit loopback host is still sent
        assertTrue(ReplayInterceptor.hostMatches("localhost", "localhost"))
    }

    @Test
    fun `star never sends loopback requests to the server`() {
        // The backend is reached as 127.0.0.1 (loopback): straight to the network
        val client = OkHttpClient.Builder()
            .addInterceptor(ReplayInterceptor(hosts = setOf("*"), port = tapedeck.port)).build()
        val body = client.newCall(Request.Builder().url(backend.url("/video")).build()).execute().use { it.body!!.string() }
        assertEquals("backend", body)
        assertEquals(0, tapedeck.requestCount)
        // Through [::1] too
        val v6 = backend.url("/video").newBuilder().host("::1").build()
        runCatching { client.newCall(Request.Builder().url(v6).build()).execute().close() }
        assertEquals(0, tapedeck.requestCount)
    }

    @Test
    fun `star sends other hosts to the server, except forward paths`() {
        // A non-loopback host name, resolved to the local backend: "*" applies to it
        val client = OkHttpClient.Builder()
            .dns(object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.1")) })
            .addInterceptor(ReplayInterceptor(hosts = setOf("*"), port = tapedeck.port, forwardPaths = setOf("/live")))
            .build()
        fun get(path: String) = client.newCall(
            Request.Builder().url("http://bypass.example.test:${backend.port}$path").build()
        ).execute().use { it.body!!.string() }

        assertEquals("tapedeck", get("/check"))
        assertEquals("backend", get("/live/stream"))
        assertEquals(1, tapedeck.requestCount)
        assertEquals(1, backend.requestCount)
    }

    @Test
    fun `forward paths on an allowed host go straight to the network`() {
        val interceptor = ReplayInterceptor(
            hosts = setOf("localhost"),
            port = tapedeck.port,
            forwardPaths = setOf("/media/v9/control/stream")
        )

        assertEquals("backend", get(interceptor, "/media/v9/control/stream"))
        assertEquals("backend", get(interceptor, "/media/v9/control/stream/123?x=1"))
        assertEquals("tapedeck", get(interceptor, "/media/v9/control/streams"))
        assertEquals("tapedeck", get(interceptor, "/epg"))
        assertEquals(2, backend.requestCount)
    }

    @Test
    fun `alwaysForward requests go straight to the network`() {
        val interceptor = ReplayInterceptor(
            hosts = setOf("localhost"),
            port = tapedeck.port,
            alwaysForward = { it.url.queryParameter("live") == "true" }
        )

        assertEquals("backend", get(interceptor, "/channels?live=true"))
        assertEquals("tapedeck", get(interceptor, "/channels"))
    }

    @Test
    fun `falls back to the network quickly when the server isn't running`() {
        val port = tapedeck.port
        tapedeck.shutdown()
        val interceptor = ReplayInterceptor(hosts = setOf("localhost"), port = port)
        get(interceptor, "/warm-up")

        // A refused loopback connection: no timeout to wait for
        val start = System.nanoTime()
        repeat(10) { assertEquals("backend", get(interceptor, "/epg")) }
        val perRequestMs = (System.nanoTime() - start) / 1_000_000 / 10
        assertTrue("fallback took $perRequestMs ms per request", perRequestMs < 100)
    }

    private fun respond(response: () -> MockResponse) = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = response()
    }

    private fun call(interceptor: ReplayInterceptor, path: String): Pair<Int, String> {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val url = backend.url(path).newBuilder().host("localhost").build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body!!.string() }
    }

    @Test
    fun `falls back to the network when the server couldn't forward the request`() {
        tapedeck.dispatcher = respond {
            MockResponse().setResponseCode(502)
                .setHeader(com.impecabel.tapedeck.core.Tapedeck.HEADER_FORWARD_FAILED, "UnknownServiceException: CLEARTEXT")
                .setBody("Tapedeck Bad Gateway")
        }

        assertEquals(200 to "backend", call(ReplayInterceptor(hosts = setOf("localhost"), port = tapedeck.port), "/img"))
        assertEquals(1, tapedeck.requestCount)
        assertEquals(1, backend.requestCount)
    }

    @Test
    fun `a backend 5xx served by the server is returned as it is`() {
        tapedeck.dispatcher = respond { MockResponse().setResponseCode(502).setBody("backend 502") }

        assertEquals(502 to "backend 502", call(ReplayInterceptor(hosts = setOf("localhost"), port = tapedeck.port), "/x"))
        assertEquals(0, backend.requestCount)
    }

    @Test
    fun `without fallbackToNetworkOnFailure a forward failure reaches the app`() {
        tapedeck.dispatcher = respond {
            MockResponse().setResponseCode(502).setHeader(com.impecabel.tapedeck.core.Tapedeck.HEADER_FORWARD_FAILED, "x")
        }
        val interceptor =
            ReplayInterceptor(hosts = setOf("localhost"), port = tapedeck.port, fallbackToNetworkOnFailure = false)

        assertEquals(502, call(interceptor, "/x").first)
        assertEquals(0, backend.requestCount)
    }

    @Test
    fun `the default port is Tapedeck's default port`() {
        assertEquals(com.impecabel.tapedeck.core.Tapedeck.DEFAULT_PORT, ReplayInterceptor(hosts = setOf("x")).port)
    }
}
