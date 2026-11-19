package com.impecabel.tapedeck.macrobenchmark

import com.impecabel.tapedeck.core.Tapedeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

// None of these depend on Tapedeck.DEFAULT_PORT being free: they use port 0 or a free port
class ReplayRuleTest {

    private fun ReplayRule.run(body: () -> Unit) = apply(object : Statement() {
        override fun evaluate() = body()
    }, Description.EMPTY).evaluate()

    private fun isListening(port: Int) = runCatching { Socket("127.0.0.1", port).close() }.isSuccess

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `the default port is the one the app's interceptor uses`() {
        assertEquals(Tapedeck.DEFAULT_PORT, ReplayRule().port)
        assertEquals(Tapedeck.DEFAULT_PORT, TapedeckServer().port)
    }

    @Test
    fun `rules with port 0 running at the same time get different free ports`() {
        val outer = ReplayRule(port = 0)
        val inner = ReplayRule(port = 0)

        outer.run {
            inner.run {
                assertNotEquals(outer.actualPort, inner.actualPort)
                assertTrue(isListening(outer.actualPort) && isListening(inner.actualPort))
            }
        }
    }

    @Test
    fun `an explicit port is honoured and served only while the test runs`() {
        val port = freePort()
        val rule = ReplayRule(port = port)
        var listening = false

        rule.run {
            assertEquals(port, rule.actualPort)
            listening = isListening(port)
        }

        assertTrue(listening)
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.exceptionOrNull() is ConnectException)
    }

    @Test
    fun `a port in use fails the test with a message naming it and the port parameter`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
            val failure = runCatching { ReplayRule(port = taken.localPort).run {} }.exceptionOrNull()

            assertTrue("$failure", failure is IllegalStateException)
            val message = failure!!.message!!
            assertTrue(message, "127.0.0.1:${taken.localPort}" in message)
            assertTrue(message, "`port`" in message)
        }
    }

    @Test
    fun `a disabled rule neither starts the server nor records`() {
        val port = freePort()
        val rule = ReplayRule(port = port, enabled = false)
        var ran = false
        var recorded = false

        rule.run {
            ran = true
            assertFalse(isListening(port))
            rule.recordOnce(onComplete = {}) { recorded = true }
        }

        assertTrue(ran)
        assertFalse(recorded)
    }

    @Test
    fun `recordOnce records on the first call, then switches to replay and does nothing more`() {
        val rule = ReplayRule(port = 0)
        var calls = 0

        rule.run {
            rule.recordOnce(onComplete = {}) { calls++ }
            assertEquals(1, calls)
            assertEquals(TapedeckMode.REPLAY, rule.server.mode)

            rule.recordOnce(onComplete = {}) { calls++ }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `the server stops even when the test fails`() {
        val rule = ReplayRule(port = 0)
        var port = -1

        val failure = runCatching {
            rule.run {
                port = rule.actualPort
                throw IllegalStateException("benchmark failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.exceptionOrNull() is ConnectException)
    }
}
