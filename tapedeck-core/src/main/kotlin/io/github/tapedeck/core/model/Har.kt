package io.github.tapedeck.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HarRoot(
    val log: HarLog
)

@Serializable
data class HarLog(
    val version: String = "1.2",
    val creator: HarCreator = HarCreator(),
    val entries: List<HarEntry> = emptyList()
)

@Serializable
data class HarCreator(
    val name: String = "Tapedeck",
    val version: String = "0.1.0"
)

@Serializable
data class HarEntry(
    val startedDateTime: String = "",
    val time: Long = 0,
    val request: HarRequest,
    val response: HarResponse,
    val cache: HarCache = HarCache(),
    val timings: HarTimings = HarTimings()
)

@Serializable
data class HarRequest(
    val method: String,
    val url: String,
    val httpVersion: String = "HTTP/1.1",
    val headers: List<HarHeader> = emptyList(),
    val queryString: List<HarQueryParam> = emptyList(),
    val postData: HarPostData? = null,
    val headersSize: Long = -1,
    val bodySize: Long = -1
)

@Serializable
data class HarResponse(
    val status: Int,
    val statusText: String = "",
    val httpVersion: String = "HTTP/1.1",
    val headers: List<HarHeader> = emptyList(),
    val content: HarContent,
    val redirectURL: String = "",
    val headersSize: Long = -1,
    val bodySize: Long = -1
)

@Serializable
data class HarHeader(
    val name: String,
    val value: String
)

@Serializable
data class HarQueryParam(
    val name: String,
    val value: String
)

@Serializable
data class HarPostData(
    val mimeType: String = "application/octet-stream",
    val text: String? = null,
    val params: List<HarParam> = emptyList()
)

@Serializable
data class HarParam(
    val name: String,
    val value: String? = null
)

@Serializable
data class HarContent(
    val size: Long,
    val mimeType: String = "application/octet-stream",
    val text: String? = null,
    val encoding: String? = null // e.g. "base64" for binary payloads
)

@Serializable
class HarCache

@Serializable
data class HarTimings(
    val send: Long = 0,
    val wait: Long = 0,
    val receive: Long = 0
)
