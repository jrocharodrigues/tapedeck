package com.impecabel.tapedeck.core.model

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
    /** Throws for URLs that [URI] rejects (e.g. a `|` or `{` in the query, which OkHttp sends unencoded): see [host]. */
    val uri: URI by lazy { URI.create(url) }

    // Parsed by hand rather than with URI, which rejects characters OkHttp leaves unencoded in queries. The path stays as
    // sent (percent-encoded), on both sides of a match
    private val parts: UrlParts by lazy { UrlParts.parse(url) }
    val host: String get() = parts.host
    val path: String get() = parts.path
    val query: String? get() = parts.query

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

internal class UrlParts(val host: String, val path: String, val query: String?) {
    companion object {
        fun parse(url: String): UrlParts {
            val afterScheme = url.substringAfter("://", url)
            val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it == -1) afterScheme.length else it }
            val authority = afterScheme.substring(0, authorityEnd).substringAfterLast('@')
            val host = if (authority.startsWith("[")) {
                authority.substringBefore(']') + "]"
            } else {
                authority.substringBefore(':')
            }
            val rest = afterScheme.substring(authorityEnd).substringBefore('#')
            val path = rest.substringBefore('?').ifEmpty { "/" }
            val query = if ('?' in rest) rest.substringAfter('?') else null
            return UrlParts(host.lowercase(), path, query)
        }
    }
}

// Request and response bodies with these content types are stored in HAR as text, the others base64-encoded
internal fun isTextMimeType(mimeType: String): Boolean {
    val type = mimeType.lowercase()
    return type.startsWith("text/") || "json" in type || "xml" in type || "javascript" in type ||
        "x-www-form-urlencoded" in type
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
        // HAR's postData has no encoding field: whether the text is base64 follows from the MIME type, both ways
        val isText = isTextMimeType(contentTypeReq)
        HarPostData(
            mimeType = contentTypeReq,
            text = if (isText) String(bodyBytes, Charsets.UTF_8) else Base64.getEncoder().encodeToString(bodyBytes)
        )
    }

    val harHeadersResp = response.headers.flatMap { (k, vals) ->
        vals.map { HarHeader(k, it) }
    }
    val contentTypeResp = response.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull() ?: "application/octet-stream"
    val isRespText = isTextMimeType(contentTypeResp)
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
        if (isTextMimeType(request.postData.mimeType)) {
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
