package io.openwaters.slackwater.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class DetailBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldTideDetail() = detail(Detail.TIDE, StartupMode.COLD)
    @Test fun warmTideDetail() = detail(Detail.TIDE, StartupMode.WARM)
    @Test fun coldCurrentDetail() = detail(Detail.CURRENT, StartupMode.COLD)
    @Test fun warmCurrentDetail() = detail(Detail.CURRENT, StartupMode.WARM)

    @Test fun coldUnavailableDetail() = unavailableDetail(StartupMode.COLD)
    @Test fun warmUnavailableDetail() = unavailableDetail(StartupMode.WARM)

    private fun unavailableDetail(mode: StartupMode) = benchmark.measureRepeated(
        packageName = "io.openwaters.slackwater.android.benchmark",
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric(), duration("launchToUnavailableDetail")),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        startupMode = mode,
        setupBlock = { pressHome() },
    ) {
        val link = Uri.parse("slackwater://station/ticon%2Faigio-aig-grc-cmems")
        measured("launchToUnavailableDetail") {
            startActivityAndWait(Intent(Intent.ACTION_VIEW, link).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await(By.text("Aigio"))
            await(By.text("Predictions unavailable"))
            await(By.text("Share station details"))
        }
    }

    private enum class Detail(val path: String, val stationName: String, val timeline: String, val unit: String, val event: String) {
        TIDE("tides/friday-harbor", "Friday Harbor", "Tide timeline", "feet", "HIGH"),
        CURRENT("currents/flat-point", "Flat Point", "Current timeline", "knots", "SLACK"),
    }

    private fun detail(station: Detail, mode: StartupMode) = benchmark.measureRepeated(
        packageName = "io.openwaters.slackwater.android.benchmark",
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric(), duration("launchToDetailReading"),
            duration("revealScheduleEvent")),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        startupMode = mode,
        setupBlock = { pressHome() },
    ) {
        val link = Uri.parse("https://slackwater.xyz/${station.path}/2025-11-06T08:00-08:00")
        measured("launchToDetailReading") {
            startActivityAndWait(Intent(Intent.ACTION_VIEW, link).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await(By.textStartsWith(station.stationName))
            await(By.desc(station.timeline))
            await(By.descContains(", ${station.unit}, Nov 6"))
        }
        val event = By.text(station.event)
        check(!device.hasObject(event)) { "The schedule is already visible. Use a compact portrait phone." }
        measured("revealScheduleEvent") {
            val lower = device.displayHeight * 4 / 5
            val upper = device.displayHeight / 3
            for (attempt in 1..10) {
                // A late-loading schedule can appear above the viewport during the downward search.
                val movingDown = attempt <= 5
                device.swipe(device.displayWidth / 2, if (movingDown) lower else upper,
                    device.displayWidth / 2, if (movingDown) upper else lower, 85)
                device.waitForIdle()
                if (device.hasObject(event)) break
            }
            await(event)
        }
    }
}
