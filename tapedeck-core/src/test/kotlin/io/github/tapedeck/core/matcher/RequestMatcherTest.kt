package io.github.tapedeck.core.matcher

import io.github.tapedeck.core.model.RecordedRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestMatcherTest {

    @Test
    fun `exact matcher matches identical url and method`() {
        val matcher = ExactUrlMatcher()
        val recorded = RecordedRequest(method = "GET", url = "https://api.example.com/items?page=1")
        val incoming = RecordedRequest(method = "GET", url = "https://api.example.com/items?page=1")
        val different = RecordedRequest(method = "GET", url = "https://api.example.com/items?page=2")

        assertTrue(matcher.matches(recorded, incoming))
        assertFalse(matcher.matches(recorded, different))
    }

    @Test
    fun `path matcher ignores query params and scheme`() {
        val matcher = PathMatcher()
        val recorded = RecordedRequest(method = "GET", url = "https://api.example.com/items?page=1&ts=100")
        val incoming = RecordedRequest(method = "GET", url = "http://api.example.com/items?page=2&ts=200")

        assertTrue(matcher.matches(recorded, incoming))
    }

    @Test
    fun `composite matcher allows replaying POST on allow-list only`() {
        val matcher = CompositeRequestMatcher(
            replayPostPaths = setOf("/auth/token", "/device/provision")
        )

        val recordedAuth = RecordedRequest(method = "POST", url = "https://auth.example.com/auth/token")
        val incomingAuth = RecordedRequest(method = "POST", url = "https://auth.example.com/auth/token")

        val recordedStream = RecordedRequest(method = "POST", url = "https://video.example.com/stream/session")
        val incomingStream = RecordedRequest(method = "POST", url = "https://video.example.com/stream/session")

        assertTrue(matcher.matches(recordedAuth, incomingAuth))
        assertFalse("Non-allowed POST should not match", matcher.matches(recordedStream, incomingStream))
    }

    @Test
    fun `composite matcher can ignore specific query params`() {
        val matcher = CompositeRequestMatcher(
            ignoredQueryParams = setOf("timestamp", "nonce")
        )

        val recorded = RecordedRequest(
            method = "GET",
            url = "https://api.example.com/data?key=abc&timestamp=1000&nonce=1"
        )
        val incoming = RecordedRequest(
            method = "GET",
            url = "https://api.example.com/data?key=abc&timestamp=2000&nonce=2"
        )

        assertTrue(matcher.matches(recorded, incoming))
    }
}
