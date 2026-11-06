package io.openwaters.slackwater.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
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
            check(device.wait(Until.hasObject(By.text("Measure")), 120_000))
        }
    ) {
        device.findObject(By.text("Measure")).click()
        check(device.wait(Until.hasObject(By.textStartsWith("Measured ")), 120_000))
    }
}
