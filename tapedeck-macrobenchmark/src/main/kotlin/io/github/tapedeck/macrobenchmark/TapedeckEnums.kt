package io.github.tapedeck.macrobenchmark

enum class TapedeckMode {
    RECORD,
    REPLAY,
    PASSTHROUGH
}

enum class OnUnmatched {
    FORWARD,
    FAIL,
    NOT_FOUND
}
