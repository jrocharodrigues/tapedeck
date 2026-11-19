package com.impecabel.tapedeck.okhttp

import com.impecabel.tapedeck.core.Tapedeck
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BypassHttpCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: Cache

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        cache = Cache(tmp.newFolder("http-cache"), 1024L * 1024L)
    }

    @After
    fun tearDown() {
        server.shutdown()
        cache.close()
    }

    // Like apps that force caching whatever the backend says
    private val forceCaching = Interceptor { chain ->
        chain.proceed(chain.request()).newBuilder()
            .header("Cache-Control", "public, max-age=3600")
            .removeHeader("Pragma")
            .build()
    }

    private fun client(bypassHttpCache: Boolean) = OkHttpClient.Builder()
        .addInterceptor(ReplayInterceptor(hosts = setOf("api.example.com"), port = server.port, bypassHttpCache = bypassHttpCache))
        .addNetworkInterceptor(forceCaching)
        .cache(cache)
        .build()

    private fun get(client: OkHttpClient, cacheControl: String? = null): String {
        val request = Request.Builder().url("https://api.example.com/feed")
            .apply { if (cacheControl != null) header("Cache-Control", cacheControl) }
            .build()
        return client.newCall(request).execute().use { it.body!!.string() }
    }

    @Test
    fun `without bypassHttpCache replayed responses are cached like real ones`() {
        server.enqueue(MockResponse().setBody("first"))
        val client = client(bypassHttpCache = false)

        assertEquals("first", get(client))
        assertEquals("first", get(client))

        assertEquals(1, server.requestCount)
        assertEquals(1, cache.writeSuccessCount())
        assertNull(server.takeRequest().getHeader(Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL))
    }

    @Test
    fun `with bypassHttpCache responses are neither stored nor served from the cache`() {
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("second"))
        val client = client(bypassHttpCache = true)

        assertEquals("first", get(client))
        assertEquals("second", get(client))

        assertEquals(2, server.requestCount)
        assertEquals(0, cache.writeSuccessCount())
        assertEquals(0, cache.size())
    }

    @Test
    fun `with bypassHttpCache an already cached response is not served`() {
        server.enqueue(MockResponse().setBody("cached"))
        server.enqueue(MockResponse().setBody("fresh"))

        assertEquals("cached", get(client(bypassHttpCache = false)))
        assertEquals("fresh", get(client(bypassHttpCache = true)))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the request's own Cache-Control is passed to the server`() {
        server.enqueue(MockResponse().setBody("a"))
        server.enqueue(MockResponse().setBody("b"))
        val client = client(bypassHttpCache = true)

        get(client)
        get(client, cacheControl = "max-age=0")

        val withoutOwn = server.takeRequest()
        assertEquals("no-cache, no-store", withoutOwn.getHeader("Cache-Control"))
        assertEquals("", withoutOwn.getHeader(Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL))
        assertEquals("max-age=0", server.takeRequest().getHeader(Tapedeck.HEADER_ORIGINAL_CACHE_CONTROL))
    }
}
