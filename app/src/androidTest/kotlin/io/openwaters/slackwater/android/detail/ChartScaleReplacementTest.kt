package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.TideRatePoint
import io.openwaters.slackwater.android.core.prediction.ChartModel
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import java.time.Instant
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChartScaleReplacementTest {
    @get:Rule val compose = createComposeRule()
    private val selected = Instant.parse("2026-11-06T18:00:00Z")
    private var density = 1f

    private fun tide(offset: Double, append: Boolean = false): PredictionSeries.Tide {
        val points = listOf(
            TidePoint(selected.minusSeconds(3600), offset - 0.2),
            TidePoint(selected, offset),
            TidePoint(selected.plusSeconds(3600), offset + 0.2),
        ) + if (append) listOf(TidePoint(selected.plusSeconds(7200), offset + 2.0)) else emptyList()
        return PredictionSeries.Tide(points, points.map { TideRatePoint(it.time, 0.0) }, emptyList())
    }

    // All the test models keep their selected value halfway between the original two extrema.
    // A stale or unnecessarily recomputed fit moves the white riding dot away from this pixel.
    private fun assertCentredDot() {
        val pixels = compose.onNodeWithTag("scaleTestPlot").captureToImage().toPixelMap()
        val y = pixels.height - (168 * density).roundToInt()
        val dot = pixels[pixels.width / 2, y]
        assertTrue("Expected the white riding dot at the plot midpoint, got $dot", dot.red > .95f && dot.green > .95f && dot.blue > .95f)
    }

    private fun exerciseReplacement(fixed: Boolean) {
        val station = StationIdentity(StationId("test"), "Test", "", Double.NaN, Double.NaN, StationKind.Tide)
        var series by mutableStateOf(tide(1.0))
        fun model(offset: Double) = ChartModel.Tide { at, _, _ -> listOf(TidePoint(at, offset)) }
        var source by mutableStateOf(if (fixed) null else model(1.0))
        compose.setContent {
            density = LocalDensity.current.density
            Box(Modifier.width(260.dp)) {
                TimelineCanvas(station, series, emptyList(), selected, selected, Modifier.testTag("scaleTestPlot"),
                    chartModel = source, fixedWindow = fixed, showSky = false, controlsVisible = false)
            }
        }
        assertCentredDot()
        if (!fixed) {
            compose.runOnIdle { series = tide(1.0, append = true) }
            assertCentredDot()
        }
        compose.runOnIdle {
            series = tide(3.0)
            if (!fixed) source = model(3.0)
        }
        assertCentredDot()
    }

    @Test fun modelReplacementRefitsButAContinuousChunkArrivalKeepsTheHeldScale() = exerciseReplacement(false)
    @Test fun boundedBlockReplacementRefitsWithoutADecodedModel() = exerciseReplacement(true)
}
