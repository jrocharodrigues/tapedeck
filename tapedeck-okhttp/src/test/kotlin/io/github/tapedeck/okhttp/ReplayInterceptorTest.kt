package io.github.tapedeck.okhttp

import io.github.tapedeck.core.Tapedeck
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.net.ConnectException

class ReplayInterceptorTest {

    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `rewrites allowed host to local mock server and preserves original url in header`() {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok"}""")
        )

        val interceptor = ReplayInterceptor(
            hosts = setOf("api.example.com"),
            port = mockServer.port
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        val request = Request.Builder()
            .url("https://api.example.com/v1/users?page=1")
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)
        assertEquals("""{"status":"ok"}""", response.body?.string())

        val recorded = mockServer.takeRequest()
        assertEquals("/v1/users?page=1", recorded.path)
        assertEquals("https://api.example.com/v1/users?page=1", recorded.getHeader(Tapedeck.HEADER_ORIGINAL_URL))
        assertEquals("https", recorded.getHeader(Tapedeck.HEADER_ORIGINAL_SCHEME))
        assertEquals("api.example.com", recorded.getHeader(Tapedeck.HEADER_ORIGINAL_HOST))
    }

    @Test
    fun `supports wildcard host matching`() {
        mockServer.enqueue(MockResponse().setResponseCode(200))

        val interceptor = ReplayInterceptor(
            hosts = setOf("*.example.com"),
            port = mockServer.port
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        val request = Request.Builder()
            .url("https://auth.sub.example.com/login")
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)

        val recorded = mockServer.takeRequest()
        assertEquals("auth.sub.example.com", recorded.getHeader(Tapedeck.HEADER_ORIGINAL_HOST))
    }

    @Test
    fun `disabled interceptor does not rewrite url`() {
        val interceptor = ReplayInterceptor(
            hosts = setOf("api.example.com"),
            port = mockServer.port,
            enabled = false
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        // We use mockServer host here so it connects, but path is /original
        val originalUrl = mockServer.url("/original")
        mockServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(originalUrl)
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)

        val recorded = mockServer.takeRequest()
        // No Tapedeck original header should be present
        assertEquals(null, recorded.getHeader(Tapedeck.HEADER_ORIGINAL_URL))
    }

    @Test
    fun `fallback to real network when server is offline and fallback is enabled`() {
        // Port with no server running
        val unusedPort = 29999

        // Let's create a real mockServer that represents the "real network" target
        val realServerUrl = mockServer.url("/feed")
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("real backend"))

        val interceptor = ReplayInterceptor(
            hosts = setOf(realServerUrl.host),
            port = unusedPort, // offline port
            fallbackToNetworkOnFailure = true
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        val request = Request.Builder()
            .url(realServerUrl)
            .build()

        // Should NOT crash, should fall back to realServerUrl
        val response = client.newCall(request).execute()
        assertEquals(200, response.code)
        assertEquals("real backend", response.body?.string())
    }
}
