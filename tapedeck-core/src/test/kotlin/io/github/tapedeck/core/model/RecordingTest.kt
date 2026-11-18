package io.github.tapedeck.core.model

import io.github.tapedeck.core.matcher.ExactUrlMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `add, find and clear entries in Recording`() {
        val recording = Recording()
        val exchange = RecordedExchange(
            request = RecordedRequest(method = "GET", url = "https://api.example.com/data"),
            response = RecordedResponse(code = 200, message = "OK")
        )

        recording.add(exchange)
        assertEquals(1, recording.size())

        val found = recording.find(RecordedRequest(method = "GET", url = "https://api.example.com/data"), ExactUrlMatcher())
        assertNotNull(found)
        assertEquals(200, found?.response?.code)

        val notFound = recording.find(RecordedRequest(method = "GET", url = "https://api.example.com/other"), ExactUrlMatcher())
        assertNull(notFound)

        recording.clear()
        assertEquals(0, recording.size())
    }

    @Test
    fun `save and load recording from HAR file`() {
        val file = tempFolder.newFile("test_recording.har")
        val recording = Recording()
        recording.add(
            RecordedExchange(
                request = RecordedRequest(method = "GET", url = "https://api.example.com/test"),
                response = RecordedResponse(code = 200, message = "OK", body = "hello".toByteArray())
            )
        )

        recording.saveToHar(file)

        val restored = Recording.loadFromHar(file)
        assertEquals(1, restored.size())
        val entry = restored.find(RecordedRequest(method = "GET", url = "https://api.example.com/test"), ExactUrlMatcher())
        assertNotNull(entry)
        assertEquals("hello", String(entry?.response?.body ?: byteArrayOf()))
    }
}
