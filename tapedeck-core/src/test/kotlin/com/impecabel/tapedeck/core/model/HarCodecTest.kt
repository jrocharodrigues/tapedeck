package com.impecabel.tapedeck.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HarCodecTest {

    @Test
    fun `encode and decode HAR entries roundtrip`() {
        val exchange = RecordedExchange(
            id = "test-123",
            durationMs = 150,
            request = RecordedRequest(
                method = "GET",
                url = "https://api.example.com/v1/feed?category=tech",
                headers = mapOf("Accept" to listOf("application/json"))
            ),
            response = RecordedResponse(
                code = 200,
                message = "OK",
                headers = mapOf("Content-Type" to listOf("application/json")),
                body = """{"items": [1, 2, 3]}""".toByteArray(Charsets.UTF_8)
            )
        )

        val harEntry = exchange.toHarEntry()
        val json = HarCodec.encode(listOf(harEntry))

        assertTrue(json.contains("https://api.example.com/v1/feed?category=tech"))
        assertTrue(json.contains("200"))

        val decodedRoot = HarCodec.decode(json)
        assertEquals(1, decodedRoot.log.entries.size)

        val restoredExchange = decodedRoot.log.entries.first().toRecordedExchange()
        assertEquals("GET", restoredExchange.request.method)
        assertEquals("https://api.example.com/v1/feed?category=tech", restoredExchange.request.url)
        assertEquals(200, restoredExchange.response.code)
        assertEquals("""{"items": [1, 2, 3]}""", String(restoredExchange.response.body!!, Charsets.UTF_8))
    }

    @Test
    fun `binary payloads encoded as base64 in HAR`() {
        val binaryData = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        val exchange = RecordedExchange(
            request = RecordedRequest(
                method = "GET",
                url = "https://cdn.example.com/image.png"
            ),
            response = RecordedResponse(
                code = 200,
                headers = mapOf("Content-Type" to listOf("image/png")),
                body = binaryData
            )
        )

        val harEntry = exchange.toHarEntry()
        assertEquals("base64", harEntry.response.content.encoding)

        val json = HarCodec.encode(listOf(harEntry))
        val restored = HarCodec.decode(json).log.entries.first().toRecordedExchange()
        assertTrue(binaryData.contentEquals(restored.response.body!!))
    }
}
