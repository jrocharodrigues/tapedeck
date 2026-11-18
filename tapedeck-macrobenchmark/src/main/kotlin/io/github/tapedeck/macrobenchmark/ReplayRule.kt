package io.github.tapedeck.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import io.github.tapedeck.core.Tapedeck
import io.github.tapedeck.core.matcher.CompositeRequestMatcher
import io.github.tapedeck.core.matcher.RequestMatcher
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.logging.Logger

class ReplayRule(
    val port: Int = Tapedeck.DEFAULT_PORT,
    val replayPost: List<String> = emptyList(),
    val latencyMs: Long = 100L,
    val recordServerErrors: Boolean = false,
    val onUnmatched: OnUnmatched = OnUnmatched.FORWARD,
    val matcher: RequestMatcher = CompositeRequestMatcher(replayPostPaths = replayPost.toSet()),
    val harOutputFile: File? = null
) : TestRule {

    private val logger = Logger.getLogger("ReplayRule")

    val server = TapedeckServer(
        port = port,
        replayPost = replayPost,
        latencyMs = latencyMs,
        recordServerErrors = recordServerErrors,
        onUnmatched = onUnmatched,
        matcher = matcher,
        harOutputFile = harOutputFile
    )

    private var hasRecorded = false

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
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
     * Subsequent invocations within the same test are no-ops.
     *
     * Automatically kills the target application after recording so that subsequent
     * benchmark iterations start from a fresh cold/warm state in REPLAY mode.
     */
    fun recordOnce(scope: MacrobenchmarkScope, block: () -> Unit) {
        if (!hasRecorded) {
            server.mode = TapedeckMode.RECORD
            try {
                block()
            } finally {
                scope.killProcess()
                server.mode = TapedeckMode.REPLAY
                hasRecorded = true
            }
        }
    }

    /**
     * Generic overload of [recordOnce] that allows a custom teardown callback [onComplete].
     */
    fun recordOnce(onComplete: (() -> Unit)? = null, block: () -> Unit) {
        if (!hasRecorded) {
            server.mode = TapedeckMode.RECORD
            try {
                block()
            } finally {
                onComplete?.invoke()
                server.mode = TapedeckMode.REPLAY
                hasRecorded = true
            }
        }
    }

    fun report(): TapedeckReport = server.report()
}
