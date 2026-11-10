package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.CurrentEvent
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import java.time.Instant
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MountedSlackCapsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun outerWindowCapsSeparateTheBlueWindowFromTheSpeedRamp() {
        val slack = Instant.parse("2026-06-01T20:00:00Z")
        val selected = slack.minusSeconds(7200)
        val station = StationIdentity(StationId("fixture"), "Fixture", "", 47.3, -122.5, StationKind.Current)
        val series = PredictionSeries.Current(
            listOf(-1, 0, 1).map { CurrentPoint(slack.plusSeconds(it * 7200L), it * 8.0) },
            listOf(CurrentEvent(slack, 0.0, CurrentEventKind.SLACK)), null, null,
        )
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            Box(Modifier.width(240.dp)) {
                TimelineCanvas(station, series, emptyList(), selected, selected, Modifier.testTag("plot"),
                    thresholdKnots = 4.0, controlsVisible = false)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("detailSkyReady")).fetchSemanticsNodes().isNotEmpty()
        }
        val pixels = compose.onNodeWithTag("plot").captureToImage().toPixelMap()
        for (side in listOf(-1, 1)) {
            // At 200 seconds per dp, slack is 36 dp right of centre and the window spans ±18 dp.
            fun stroke(offset: Double, blue: Boolean): Boolean {
                val x = (pixels.width / 2f + (36 + side * offset) * density).toInt()
                val y = pixels.height - (168 * density).roundToInt() - (side * offset * 72 / (36 * 1.05) * density).roundToInt()
                return (-2..2).any { dy ->
                    val color = pixels[x, y + dy]
                    if (blue) color.blue > .85f && color.green > .62f && color.red < .4f
                    else color.red > .85f && color.green > .4f && color.blue < .6f
                }
            }
            assertTrue("Missing blue window on side $side", stroke(16.0, true))
            assertTrue("Window crosses outer cap on side $side", !stroke(19.2, true))
            assertTrue("Speed ramp crosses outer cap on side $side", !stroke(19.2, false))
            assertTrue("Missing speed ramp on side $side", stroke(23.0, false))
        }
    }
}
