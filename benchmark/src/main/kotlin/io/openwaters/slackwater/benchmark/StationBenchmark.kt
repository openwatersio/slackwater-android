package io.openwaters.slackwater.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class StationBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStationList() = list(StartupMode.COLD)
    @Test fun warmStationList() = list(StartupMode.WARM)
    @Test fun focusStationSearch() = search(SearchJourney.FOCUS)
    @Test fun firstStationSearch() = search(SearchJourney.FIRST)
    @Test fun repeatStationSearch() = search(SearchJourney.REPEAT)
    @Test fun firstNamedPrediction() = query(repeat = false)
    @Test fun repeatNamedPrediction() = query(repeat = true)

    private fun list(mode: StartupMode) = benchmark.measureRepeated(
        packageName = APP,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric(), duration("launchToStationRow")),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        startupMode = mode,
        setupBlock = { pressHome() },
    ) {
        measured("launchToStationRow") {
            startActivityAndWait()
            awaitPreparedList()
        }
    }

    private enum class SearchJourney { FOCUS, FIRST, REPEAT }

    private fun search(journey: SearchJourney) {
        val section = if (journey == SearchJourney.FOCUS) "tapToFocusedSearch" else "queryToStationRow"
        benchmark.measureRepeated(
            packageName = APP,
            metrics = listOf(FrameTimingMetric(), duration(section)),
            iterations = 5,
            compilationMode = CompilationMode.Full(),
            setupBlock = {
                killProcess()
                startActivityAndWait()
                awaitPreparedList()
                if (journey != SearchJourney.FOCUS) {
                    await(By.text("Search")).click()
                    val input = await(searchField)
                    if (journey == SearchJourney.REPEAT) {
                        val previous = By.descStartsWith("Seattle,").clickable(true)
                        check(!device.hasObject(previous))
                        input.text = "Seattle"
                        await(previous)
                    }
                }
            },
        ) {
            if (journey == SearchJourney.FOCUS) {
                val button = await(By.text("Search"))
                measured(section) {
                    button.click()
                    await(searchField)
                }
            } else {
                val input = await(searchField)
                val result = By.descStartsWith("Port Townsend,").clickable(true)
                check(!device.hasObject(result))
                measured(section) {
                    input.text = "Port Townsend"
                    await(result)
                }
            }
        }
    }

    private fun query(repeat: Boolean) = benchmark.measureRepeated(
        packageName = APP,
        metrics = listOf(FrameTimingMetric(), duration("submitToTideAnswer")),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        setupBlock = {
            killProcess()
            startActivityAndWait(Intent(Intent.ACTION_VIEW, Uri.parse("slackwater-query://prediction/LOW"))
                .setClassName(APP, "io.openwaters.slackwater.android.benchmark.LowTideShortcut"))
            if (repeat) {
                enterPlace("Port Townsend")
                await(By.text("Show prediction")).click()
                await(By.textStartsWith("In about "))
            }
            enterPlace("Friday Harbor")
            check(device.wait(Until.gone(By.textStartsWith("In about ")), BENCHMARK_TIMEOUT_MS))
        },
    ) {
        val submit = await(By.text("Show prediction"))
        measured("submitToTideAnswer") {
            submit.click()
            await(By.textStartsWith("In about "))
        }
    }

    private fun MacrobenchmarkScope.awaitPreparedList() {
        await(listRow, "Deny location, skip the Friday Harbor tour, return to the list and select All.")
    }

    private fun MacrobenchmarkScope.enterPlace(name: String) {
        val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), BENCHMARK_TIMEOUT_MS)
        check(!fields.isNullOrEmpty()) { "Place field unavailable" }
        fields.first().text = name
    }

    private companion object {
        val listRow = By.descStartsWith("Friday Harbor,").clickable(true)
        val searchField = By.clazz("android.widget.EditText").focused(true)
        const val APP = "io.openwaters.slackwater.android.benchmark"
    }
}
