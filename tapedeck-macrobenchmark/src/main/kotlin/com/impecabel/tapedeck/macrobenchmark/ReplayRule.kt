package com.impecabel.tapedeck.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import com.impecabel.tapedeck.core.Tapedeck
import com.impecabel.tapedeck.core.matcher.CompositeRequestMatcher
import com.impecabel.tapedeck.core.matcher.RequestMatcher
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.logging.Logger

/**
 * Runs Tapedeck's server for each test: requests the app's `ReplayInterceptor` sends to it are recorded during
 * [recordOnce] and replayed afterwards.
 *
 * The server listens on 127.0.0.1:[port], [Tapedeck.DEFAULT_PORT] by default: the port the app's `ReplayInterceptor`
 * sends requests to unless it's given the same other one. It fails to start, naming the port, when the port is in use.
 * With 0 it takes a free port (see [actualPort]), e.g. for JVM tests. While no test runs, nothing listens there: the
 * app's connections are refused at once and its requests go to the network.
 *
 * With [enabled] false (e.g. to measure against the real backend from an instrumentation argument), the server isn't
 * started and [recordOnce] does nothing: the app's requests go straight to the network.
 *
 * [followRedirects] (default true): the server follows the backend's redirects and the app gets the final response,
 * recorded and replayed for the original URL; it never sees the 3xx. With false, each 3xx is recorded and replayed
 * as-is, and its `Location` is served pointing back at the server, so the app's own redirect handling sends every hop
 * through Tapedeck as well (OkHttp follows redirects below application interceptors, where `ReplayInterceptor` can't see
 * them; it can't be a network interceptor either, as those may not change the host). An app that handles redirects
 * itself sees that rewritten `Location`.
 */
class ReplayRule(
    val port: Int = Tapedeck.DEFAULT_PORT,
    val replayPost: List<String> = emptyList(),
    val latencyMs: Long = 100L,
    val recordServerErrors: Boolean = false,
    val onUnmatched: OnUnmatched = OnUnmatched.FORWARD,
    val matcher: RequestMatcher = CompositeRequestMatcher(replayPostPaths = replayPost.toSet()),
    val harOutputFile: File? = null,
    val enabled: Boolean = true,
    val followRedirects: Boolean = true
) : TestRule {

    private val logger = Logger.getLogger("ReplayRule")

    val server = TapedeckServer(
        port = port,
        replayPost = replayPost,
        latencyMs = latencyMs,
        recordServerErrors = recordServerErrors,
        onUnmatched = onUnmatched,
        matcher = matcher,
        harOutputFile = harOutputFile,
        followRedirects = followRedirects
    )

    private var hasRecorded = false

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                if (!enabled) {
                    logger.info("ReplayRule disabled: the app talks to the real backend.")
                    base.evaluate()
                    return
                }
                server.start()
                hasRecorded = false
                try {
                    base.evaluate()
                } finally {
                    val report = server.report()
                    logger.info(report.toString())
                    server.stop()
                }
            }
        }
    }

    /**
     * Executes [block] in RECORD mode before the first measured iteration.
     * Subsequent invocations within the same test are no-ops, and so is every invocation when the rule isn't [enabled].
     *
     * Automatically kills the target application after recording so that subsequent
     * benchmark iterations start from a fresh cold/warm state in REPLAY mode.
     */
    fun recordOnce(scope: MacrobenchmarkScope, block: () -> Unit) {
        recordOnce(onComplete = { scope.killProcess() }, block = block)
    }

    /**
     * Generic overload of [recordOnce] that allows a custom teardown callback [onComplete].
     */
    fun recordOnce(onComplete: (() -> Unit)? = null, block: () -> Unit) {
        if (!enabled || hasRecorded) return
        server.mode = TapedeckMode.RECORD
        try {
            block()
        } finally {
            try {
                onComplete?.invoke()
            } finally {
                server.mode = TapedeckMode.REPLAY
                hasRecorded = true
            }
        }
    }

    /** The port the server listens on; only while a test runs. */
    val actualPort: Int get() = server.actualPort

    fun report(): TapedeckReport = server.report()
}
