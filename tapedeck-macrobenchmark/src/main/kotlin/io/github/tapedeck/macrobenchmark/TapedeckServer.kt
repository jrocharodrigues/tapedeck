package io.github.tapedeck.macrobenchmark

import io.github.tapedeck.core.Tapedeck
import io.github.tapedeck.core.matcher.CompositeRequestMatcher
import io.github.tapedeck.core.matcher.RequestMatcher
import io.github.tapedeck.core.model.Recording
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.net.InetAddress
import java.util.logging.Logger

class TapedeckServer(
    val port: Int = Tapedeck.DEFAULT_PORT,
    val replayPost: List<String> = emptyList(),
    val latencyMs: Long = 100L,
    val recordServerErrors: Boolean = false,
    val onUnmatched: OnUnmatched = OnUnmatched.FORWARD,
    val matcher: RequestMatcher = CompositeRequestMatcher(replayPostPaths = replayPost.toSet()),
    val forwardingClient: OkHttpClient = OkHttpClient.Builder().build(),
    val harOutputFile: File? = null
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
        onUnmatched = onUnmatched
    )

    fun start() {
        if (mockWebServer != null) return

        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start(InetAddress.getByName("127.0.0.1"), port)
        mockWebServer = server
        logger.info("TapedeckServer started on 127.0.0.1:$port")
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

    var mode: TapedeckMode
        get() = dispatcher.mode
        set(value) {
            dispatcher.mode = value
        }

    fun report(): TapedeckReport = dispatcher.report()
}
