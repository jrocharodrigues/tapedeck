package com.impecabel.tapedeck.core.matcher

import com.impecabel.tapedeck.core.model.RecordedRequest

fun interface RequestMatcher {
    fun matches(recorded: RecordedRequest, incoming: RecordedRequest): Boolean

    /**
     * How well [recorded] matches [incoming]: 0 is no match, and higher is better. A recording answers a request with the
     * best-scoring entry, and with the earliest recorded one among equal scores.
     *
     * Defaults to 1 for any match. Override it when a matcher has several levels of match (see [CompositeRequestMatcher]).
     */
    fun score(recorded: RecordedRequest, incoming: RecordedRequest): Int = if (matches(recorded, incoming)) 1 else 0
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

/**
 * Matches GET and HEAD requests, and POST requests to [replayPostPaths]: by method and full URL (score [EXACT]), or else
 * by method, host and path, ignoring the query (score [PATH]). Across a whole recording, an exact match always wins over
 * a path-only one.
 */
class CompositeRequestMatcher(
    private val replayPostPaths: Set<String> = emptySet(),
    private val matchPostBodyHash: Boolean = false,
    private val ignoredQueryParams: Set<String> = emptySet()
) : RequestMatcher {

    override fun matches(recorded: RecordedRequest, incoming: RecordedRequest): Boolean = score(recorded, incoming) > 0

    override fun score(recorded: RecordedRequest, incoming: RecordedRequest): Int {
        if (!recorded.method.equals(incoming.method, ignoreCase = true)) return NONE

        when (incoming.method.uppercase()) {
            "GET", "HEAD" -> Unit
            "POST" -> {
                val isPathAllowed = replayPostPaths.any { path ->
                    incoming.path.equals(path, ignoreCase = true) || incoming.path.endsWith(path)
                }
                if (!isPathAllowed) return NONE
                if (matchPostBodyHash && recorded.bodySha256 != incoming.bodySha256) return NONE
            }
            // PUT, DELETE, PATCH, etc. are not replayed
            else -> return NONE
        }

        if (urlsMatch(recorded, incoming)) return EXACT
        return if (PATH_MATCHER.matches(recorded, incoming)) PATH else NONE
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

    companion object {
        const val NONE = 0
        const val PATH = 1
        const val EXACT = 2

        private val PATH_MATCHER = PathMatcher()
    }
}
