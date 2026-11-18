package com.impecabel.tapedeck.core.model

import com.impecabel.tapedeck.core.matcher.CompositeRequestMatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingMatchingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun exchange(method: String, url: String, body: String) = RecordedExchange(
        request = RecordedRequest(method = method, url = url),
        response = RecordedResponse(code = 200, body = body.toByteArray())
    )

    private fun Recording.bodyFor(method: String, url: String): String? =
        find(RecordedRequest(method = method, url = url), CompositeRequestMatcher())?.response?.body?.let { String(it) }

    @Test
    fun `exact URL match wins over an earlier entry matching only the path`() {
        val recording = Recording(
            listOf(
                exchange("GET", "https://api.example.com/epg?day=1", "day 1"),
                exchange("GET", "https://api.example.com/epg?day=2", "day 2")
            )
        )

        assertEquals("day 2", recording.bodyFor("GET", "https://api.example.com/epg?day=2"))
        assertEquals("day 1", recording.bodyFor("GET", "https://api.example.com/epg?day=1"))
    }

    @Test
    fun `falls back to the first entry with the same host and path when no URL matches exactly`() {
        val recording = Recording(
            listOf(
                exchange("GET", "https://api.example.com/epg?day=1", "day 1"),
                exchange("GET", "https://api.example.com/epg?day=2", "day 2")
            )
        )

        assertEquals("day 1", recording.bodyFor("GET", "https://api.example.com/epg?day=3"))
        assertNull(recording.bodyFor("GET", "https://other.example.com/epg?day=1"))
    }

    @Test
    fun `the first recorded response wins between identical requests`() {
        val recording = Recording(
            listOf(
                exchange("GET", "https://api.example.com/config", "first"),
                exchange("GET", "https://api.example.com/config", "second")
            )
        )

        assertEquals("first", recording.bodyFor("GET", "https://api.example.com/config"))
    }

    @Test
    fun `urls with characters URI rejects are matched instead of failing`() {
        val url = "https://api.example.com/search?q=a|b&f={x}"
        val request = RecordedRequest(method = "GET", url = url)
        assertEquals("api.example.com", request.host)
        assertEquals("/search", request.path)
        assertEquals("q=a|b&f={x}", request.query)

        val recording = Recording(listOf(exchange("GET", url, "found")))
        assertEquals("found", recording.bodyFor("GET", url))
        assertEquals("found", recording.bodyFor("GET", "https://api.example.com/search?q=c"))
    }

    @Test
    fun `host, path and query are parsed from any URL shape`() {
        RecordedRequest(method = "GET", url = "https://user@API.example.com:8443").let {
            assertEquals("api.example.com", it.host)
            assertEquals("/", it.path)
            assertNull(it.query)
        }
        RecordedRequest(method = "GET", url = "http://[::1]:28765/a/b?x=1#frag").let {
            assertEquals("[::1]", it.host)
            assertEquals("/a/b", it.path)
            assertEquals("x=1", it.query)
        }
    }

    @Test
    fun `text request bodies of any text type survive a HAR round trip`() {
        val bodies = mapOf(
            "application/xml" to "<a>1</a>",
            "application/x-www-form-urlencoded" to "grant_type=password&user=x",
            "text/plain; charset=utf-8" to "héllo",
            "application/octet-stream" to "\u0000\u0001binary"
        )
        val file = tempFolder.newFile("bodies.har")
        val recording = Recording()
        bodies.forEach { (type, body) ->
            recording.add(
                RecordedExchange(
                    request = RecordedRequest(
                        method = "POST",
                        url = "https://api.example.com/${type.substringAfter('/').substringBefore(';')}",
                        headers = mapOf("Content-Type" to listOf(type)),
                        body = body.toByteArray()
                    ),
                    response = RecordedResponse(code = 200)
                )
            )
        }

        recording.saveToHar(file)
        val restored = Recording.loadFromHar(file)

        bodies.entries.forEachIndexed { i, (type, body) ->
            assertArrayEquals(type, body.toByteArray(), restored.entries[i].request.body)
        }
    }
}
