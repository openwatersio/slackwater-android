package io.openwaters.slackwater.benchmark

import android.os.Trace
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class StationBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStationShell() = shell(StartupMode.COLD)
    @Test fun warmStationShell() = shell(StartupMode.WARM)
    @Test fun firstNamedPrediction() = query(repeat = false)
    @Test fun repeatNamedPrediction() = query(repeat = true)

    private fun shell(mode: StartupMode) = benchmark.measureRepeated(
        packageName = APP,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric(), duration("launchToStationName")),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        startupMode = mode,
        setupBlock = { pressHome() },
    ) {
        measured("launchToStationName") {
            startActivityAndWait()
            await(By.text("Friday Harbor"))
        }
    }

    private fun query(repeat: Boolean) = benchmark.measureRepeated(
        packageName = APP,
        metrics = listOf(FrameTimingMetric(), duration("submitToTideAnswer")),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        setupBlock = {
            killProcess()
            startActivityAndWait()
            await(By.text("Friday Harbor"))
            await(By.text("Prediction shortcuts")).click()
            await(By.text("Next low tide")).click()
            if (repeat) {
                enterPlace("Port Townsend")
                await(By.text("Show prediction")).click()
                await(By.textStartsWith("In about "))
            }
            enterPlace("Friday Harbor")
            check(device.wait(Until.gone(By.textStartsWith("In about ")), TIMEOUT))
        },
    ) {
        val submit = await(By.text("Show prediction"))
        measured("submitToTideAnswer") {
            submit.click()
            await(By.textStartsWith("In about "))
        }
    }

    private fun MacrobenchmarkScope.enterPlace(name: String) {
        val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), TIMEOUT)
        check(!fields.isNullOrEmpty()) { "Place field unavailable" }
        fields.first().text = name
    }

    private fun MacrobenchmarkScope.await(selector: BySelector) =
        checkNotNull(device.wait(Until.findObject(selector), TIMEOUT)) { "Timed out waiting for $selector" }

    private fun duration(section: String) =
        TraceSectionMetric(section, mode = TraceSectionMetric.Mode.First, targetPackageOnly = false)

    private inline fun measured(section: String, block: () -> Unit) {
        Trace.beginSection(section)
        try { block() } finally { Trace.endSection() }
    }

    private companion object {
        const val APP = "io.openwaters.slackwater.android.benchmark"
        const val TIMEOUT = 15_000L
    }
}
