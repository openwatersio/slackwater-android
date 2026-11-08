package io.openwaters.slackwater.benchmark

import android.os.SystemClock
import android.os.Trace
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.TraceSectionMetric
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2

internal const val BENCHMARK_TIMEOUT_MS = 15_000L

internal fun MacrobenchmarkScope.await(selector: BySelector, hint: String = ""): UiObject2 {
    val deadline = SystemClock.uptimeMillis() + BENCHMARK_TIMEOUT_MS
    do {
        device.findObject(selector)?.let { return it }
        SystemClock.sleep(25)
    } while (SystemClock.uptimeMillis() < deadline)
    error("Timed out waiting for $selector. $hint")
}

@OptIn(ExperimentalMetricApi::class)
internal fun duration(section: String) =
    TraceSectionMetric(section, mode = TraceSectionMetric.Mode.First, targetPackageOnly = false)

internal inline fun measured(section: String, block: () -> Unit) {
    Trace.beginSection(section)
    try { block() } finally { Trace.endSection() }
}
