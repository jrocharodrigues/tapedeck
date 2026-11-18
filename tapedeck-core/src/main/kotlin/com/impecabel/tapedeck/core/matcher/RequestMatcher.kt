package com.impecabel.tapedeck.core.matcher

import com.impecabel.tapedeck.core.model.RecordedExchange
import com.impecabel.tapedeck.core.model.RecordedRequest

fun interface RequestMatcher {
    fun matches(recorded: RecordedRequest, incoming: RecordedRequest): Boolean
}

class ExactUrlMatcher : RequestMatcher {
    override fun matches(recorded: RecordedRequest, incoming: RecordedRequest): Boolean {
        if (!recorded.method.equals(incoming.method, ignoreCase = true)) return false
        return recorded.url == incoming.url
    }
}

class PathMatcher : RequestMatcher {
    override fun matches(recorded: RecordedRequest, incoming: RecordedRequest): Boolean {
        if (!recorded.method.equals(incoming.method, ignoreCase = true)) return false
        if (!recorded.host.equals(incoming.host, ignoreCase = true)) return false
        return recorded.path == incoming.path
    }
}

class CompositeRequestMatcher(
    private val replayPostPaths: Set<String> = emptySet(),
    private val matchPostBodyHash: Boolean = false,
    private val ignoredQueryParams: Set<String> = emptySet()
) : RequestMatcher {

    override fun matches(recorded: RecordedRequest, incoming: RecordedRequest): Boolean {
        if (!recorded.method.equals(incoming.method, ignoreCase = true)) return false

        val method = incoming.method.uppercase()
        when (method) {
            "GET", "HEAD" -> {
                // Exact match first
                if (urlsMatch(recorded, incoming)) return true
                // Fallback to path match
                return PathMatcher().matches(recorded, incoming)
            }
            "POST" -> {
                val isPathAllowed = replayPostPaths.any { path ->
                    incoming.path.equals(path, ignoreCase = true) || incoming.path.endsWith(path)
                }
                if (!isPathAllowed) return false

                if (matchPostBodyHash) {
                    if (recorded.bodySha256 != incoming.bodySha256) return false
                }

                if (urlsMatch(recorded, incoming)) return true
                return PathMatcher().matches(recorded, incoming)
            }
            else -> {
                // PUT, DELETE, PATCH, etc. are not replayed by default
                return false
            }
        }
    }

    private fun urlsMatch(recorded: RecordedRequest, incoming: RecordedRequest): Boolean {
        if (ignoredQueryParams.isEmpty()) {
            return recorded.url == incoming.url
        }

        // Strip ignored query params and compare
        val recordedFiltered = stripIgnoredQueryParams(recorded.url)
        val incomingFiltered = stripIgnoredQueryParams(incoming.url)
        return recordedFiltered == incomingFiltered
    }

    private fun stripIgnoredQueryParams(rawUrl: String): String {
        val qIdx = rawUrl.indexOf('?')
        if (qIdx == -1) return rawUrl
        val baseUrl = rawUrl.substring(0, qIdx)
        val query = rawUrl.substring(qIdx + 1)
        val filteredParams = query.split('&').filter { param ->
            val key = param.substringBefore('=')
            !ignoredQueryParams.contains(key)
        }
        return if (filteredParams.isEmpty()) baseUrl else "$baseUrl?${filteredParams.joinToString("&")}"
    }
}
