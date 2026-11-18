package io.github.tapedeck.sample.benchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tapedeck.macrobenchmark.ReplayRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @get:Rule
    val replayRule = ReplayRule(
        latencyMs = 50L
    )

    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = "io.github.tapedeck.sample",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            // Records live backend response once during warm-up iteration
            replayRule.recordOnce(this) {
                pressHome()
                startActivityAndWait()
            }
        }
    ) {
        pressHome()
        startActivityAndWait()
    }
}
