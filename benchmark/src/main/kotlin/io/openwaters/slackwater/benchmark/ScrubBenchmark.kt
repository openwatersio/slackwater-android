package io.openwaters.slackwater.benchmark

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class ScrubBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun scrubFridayHarborAcrossDays() = benchmark.measureRepeated(
        packageName = "io.openwaters.slackwater.android.benchmark",
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            duration("scrubAcrossDays")),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        setupBlock = {
            killProcess()
            val link = Uri.parse("https://slackwater.xyz/tides/friday-harbor/2025-11-06T08:00-08:00")
            startActivityAndWait(Intent(Intent.ACTION_VIEW, link).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await(By.descContains(", feet, Nov 6 · 8:00"))
        },
    ) {
        val chart = await(By.desc("Tide timeline")).visibleBounds
        val left = chart.left + chart.width() / 5
        val right = chart.right - chart.width() / 5
        val date = Regex(", feet, Nov (\\d+) ·")
        val reading = By.descContains(", feet, Nov ")
        fun settledDay(): Int {
            val deadline = SystemClock.uptimeMillis() + BENCHMARK_TIMEOUT_MS
            do {
                val before = await(reading).contentDescription
                SystemClock.sleep(750)
                if (await(reading).contentDescription == before) {
                    return requireNotNull(date.find(before)) { before }.groupValues[1].toInt()
                }
            } while (SystemClock.uptimeMillis() < deadline)
            error("Tide reading did not settle after scrubbing")
        }
        measured("scrubAcrossDays") {
            repeat(6) { check(device.swipe(right, chart.centerY(), left, chart.centerY(), 100)) }
            val future = settledDay()
            check(future > 6) { "Forward scrubs stopped on November $future" }
            repeat(6) { check(device.swipe(left, chart.centerY(), right, chart.centerY(), 100)) }
            val returned = settledDay()
            check(returned < future) { "Reverse scrubs stopped on November $returned after reaching November $future" }
        }
    }
}
