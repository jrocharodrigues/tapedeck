package io.github.tapedeck.core.model

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

data class RecordedExchange(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now(),
    val durationMs: Long = 0,
    val request: RecordedRequest,
    val response: RecordedResponse
)

data class RecordedRequest(
    val method: String,
    val url: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray? = null
) {
    val uri: URI by lazy { URI.create(url) }
    val host: String get() = uri.host ?: ""
    val path: String get() = uri.path ?: "/"
    val query: String? get() = uri.query

    val bodySha256: String? by lazy {
        body?.let { bytes ->
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedRequest) return false
        if (method != other.method) return false
        if (url != other.url) return false
        if (headers != other.headers) return false
        if (body != null) {
            if (other.body == null) return false
            if (!body.contentEquals(other.body)) return false
        } else if (other.body != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}

data class RecordedResponse(
    val code: Int,
    val message: String = "",
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray? = null
) {
    val isSuccessful: Boolean get() = code in 200..299
    val isServerError: Boolean get() = code in 500..599
    val isClientError: Boolean get() = code in 400..499

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedResponse) return false
        if (code != other.code) return false
        if (message != other.message) return false
        if (headers != other.headers) return false
        if (body != null) {
            if (other.body == null) return false
            if (!body.contentEquals(other.body)) return false
        } else if (other.body != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + message.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}

fun RecordedExchange.toHarEntry(): HarEntry {
    val harHeadersReq = request.headers.flatMap { (k, vals) ->
        vals.map { HarHeader(k, it) }
    }
    val contentTypeReq = request.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull() ?: "application/octet-stream"
    val postData = request.body?.let { bodyBytes ->
        val isText = contentTypeReq.contains("json") || contentTypeReq.contains("text") || contentTypeReq.contains("xml")
        HarPostData(
            mimeType = contentTypeReq,
            text = if (isText) String(bodyBytes, Charsets.UTF_8) else Base64.getEncoder().encodeToString(bodyBytes)
        )
    }

    val harHeadersResp = response.headers.flatMap { (k, vals) ->
        vals.map { HarHeader(k, it) }
    }
    val contentTypeResp = response.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull() ?: "application/octet-stream"
    val isRespText = contentTypeResp.contains("json") || contentTypeResp.contains("text") || contentTypeResp.contains("xml") || contentTypeResp.contains("javascript")
    val content = HarContent(
        size = response.body?.size?.toLong() ?: 0L,
        mimeType = contentTypeResp,
        text = response.body?.let { bodyBytes ->
            if (isRespText) String(bodyBytes, Charsets.UTF_8) else Base64.getEncoder().encodeToString(bodyBytes)
        },
        encoding = if (isRespText || response.body == null) null else "base64"
    )

    return HarEntry(
        startedDateTime = timestamp.toString(),
        time = durationMs,
        request = HarRequest(
            method = request.method,
            url = request.url,
            headers = harHeadersReq,
            postData = postData,
            bodySize = request.body?.size?.toLong() ?: 0L
        ),
        response = HarResponse(
            status = response.code,
            statusText = response.message,
            headers = harHeadersResp,
            content = content,
            bodySize = response.body?.size?.toLong() ?: 0L
        )
    )
}

fun HarEntry.toRecordedExchange(): RecordedExchange {
    val reqHeaders = request.headers.groupBy({ it.name }, { it.value })
    val reqBody = request.postData?.text?.let { txt ->
        if (request.postData.mimeType.contains("json") || request.postData.mimeType.contains("text")) {
            txt.toByteArray(Charsets.UTF_8)
        } else {
            try {
                Base64.getDecoder().decode(txt)
            } catch (e: Exception) {
                txt.toByteArray(Charsets.UTF_8)
            }
        }
    }

    val respHeaders = response.headers.groupBy({ it.name }, { it.value })
    val respBody = response.content.text?.let { txt ->
        if (response.content.encoding == "base64") {
            Base64.getDecoder().decode(txt)
        } else {
            txt.toByteArray(Charsets.UTF_8)
        }
    }

    return RecordedExchange(
        durationMs = time,
        request = RecordedRequest(
            method = request.method,
            url = request.url,
            headers = reqHeaders,
            body = reqBody
        ),
        response = RecordedResponse(
            code = response.status,
            message = response.statusText,
            headers = respHeaders,
            body = respBody
        )
    )
}
