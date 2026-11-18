package io.github.tapedeck.macrobenchmark

data class TapedeckReport(
    val recordedCount: Int,
    val replayedCount: Int,
    val forwardedCount: Int,
    val unmatchedCount: Int,
    val totalRequests: Int = recordedCount + replayedCount + forwardedCount
) {
    override fun toString(): String {
        return buildString {
            appendLine("=== Tapedeck Benchmark Report ===")
            appendLine("Total Requests Handled : $totalRequests")
            appendLine("  - Recorded (Warmup)  : $recordedCount")
            appendLine("  - Replayed           : $replayedCount")
            appendLine("  - Forwarded to Net   : $forwardedCount")
            appendLine("  - Unmatched Requests : $unmatchedCount")
            appendLine("=================================")
        }
    }
}
