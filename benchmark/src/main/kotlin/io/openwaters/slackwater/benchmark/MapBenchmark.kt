package io.openwaters.slackwater.benchmark

import android.content.Intent
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class MapBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun harbourVisibleSet() = measureCamera("harbour")
    @Test fun continentVisibleSet() = measureCamera("continent")

    private fun measureCamera(camera: String) = benchmark.measureRepeated(
        packageName = "io.openwaters.slackwater.android.benchmark",
        metrics = listOf(TraceSectionMetric("visibleStationStates", TraceSectionMetric.Mode.First)),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        setupBlock = {
            killProcess()
            startActivityAndWait(Intent().setClassName(packageName,
                "io.openwaters.slackwater.android.map.MapBenchmarkActivity").putExtra("camera", camera))
            check(device.wait(Until.hasObject(By.text(Pattern.compile("Measure", Pattern.CASE_INSENSITIVE))), 120_000))
        }
    ) {
        device.findObject(By.text(Pattern.compile("Measure", Pattern.CASE_INSENSITIVE))).click()
        val finished = checkNotNull(device.wait(
            Until.findObject(By.text(Pattern.compile("Measured [0-9]+ stations", Pattern.CASE_INSENSITIVE))), 120_000))
        Log.i("MapBenchmark", "$camera: ${finished.text}")
    }
}
