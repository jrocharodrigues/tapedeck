package com.impecabel.tapedeck.macrobenchmark

import com.impecabel.tapedeck.core.Tapedeck
import com.impecabel.tapedeck.core.matcher.CompositeRequestMatcher
import com.impecabel.tapedeck.core.matcher.RequestMatcher
import com.impecabel.tapedeck.core.model.Recording
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.util.logging.Logger

/**
 * Tapedeck's server: MockWebServer on 127.0.0.1, on [port] ([Tapedeck.DEFAULT_PORT] by default, which the app's
 * `ReplayInterceptor` uses too), or with 0 on a free port chosen when it starts (see [actualPort]).
 */
class TapedeckServer(
    val port: Int = Tapedeck.DEFAULT_PORT,
    val replayPost: List<String> = emptyList(),
    val latencyMs: Long = 100L,
    val recordServerErrors: Boolean = false,
    val onUnmatched: OnUnmatched = OnUnmatched.FORWARD,
    val matcher: RequestMatcher = CompositeRequestMatcher(replayPostPaths = replayPost.toSet()),
    val forwardingClient: OkHttpClient = OkHttpClient.Builder().build(),
    val harOutputFile: File? = null,
    val followRedirects: Boolean = true
) {
    private val logger = Logger.getLogger("TapedeckServer")
    private var mockWebServer: MockWebServer? = null

    val recording = Recording()

    val dispatcher = TapedeckDispatcher(
        recording = recording,
        matcher = matcher,
        forwardingClient = forwardingClient,
        mode = TapedeckMode.RECORD,
        latencyMs = latencyMs,
        recordServerErrors = recordServerErrors,
        onUnmatched = onUnmatched,
        followRedirects = followRedirects
    )

    fun start() {
        if (mockWebServer != null) return

        val server = MockWebServer()
        server.dispatcher = dispatcher
        try {
            server.start(InetAddress.getByName("127.0.0.1"), port)
        } catch (e: BindException) {
            throw IllegalStateException(
                "Tapedeck can't listen on 127.0.0.1:$port, already in use (another benchmark run, or a server left " +
                    "running?). Free it, or pass another `port` to ReplayRule and the same `port` to ReplayInterceptor.",
                e
            )
        }
        mockWebServer = server
        logger.info("TapedeckServer started on 127.0.0.1:${server.port}")
    }

    fun stop() {
        mockWebServer?.let {
            try {
                if (harOutputFile != null) {
                    recording.saveToHar(harOutputFile)
                    logger.info("Saved HAR recording to ${harOutputFile.absolutePath}")
                }
                it.shutdown()
                logger.info("TapedeckServer stopped.")
            } catch (e: Exception) {
                logger.warning("Error stopping TapedeckServer: ${e.message}")
            } finally {
                mockWebServer = null
            }
        }
    }

    /** The port the server listens on; only while it runs. */
    val actualPort: Int
        get() = checkNotNull(mockWebServer) { "TapedeckServer isn't running" }.port

    var mode: TapedeckMode
        get() = dispatcher.mode
        set(value) {
            dispatcher.mode = value
        }

    fun report(): TapedeckReport = dispatcher.report()
}
