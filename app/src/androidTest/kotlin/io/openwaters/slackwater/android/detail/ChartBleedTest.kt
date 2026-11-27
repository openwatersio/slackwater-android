package io.openwaters.slackwater.android.detail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.TideRatePoint
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.core.timeline.SkyBody
import io.openwaters.slackwater.android.core.timeline.SkyFrame
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The strip runs to the page's edges and its sky starts at the top of the page (scrubber §1). */
class ChartBleedTest {
    @get:Rule val compose = createComposeRule()
    // 20:00 UTC is just past Friday Harbor's midday, so the sky's top is a day blue.
    private val now = Instant.parse("2026-06-01T20:00:00Z")
    private val noon = SkyFrame(
        sun = SkyBody(x = 0.5, altitude = 30.0), moon = SkyBody(x = 0.25, altitude = 30.0),
        phase = 0.5, limbDegrees = 0.0, obscuration = 0.0, umbra = 0.0, wash = 0.0, stars = emptyList(),
    )

    private fun isDayBlue(color: Int) = Color.blue(color) > 100 && Color.blue(color) > Color.red(color) + 60

    @Test fun theSkyGradientStartsAtThePageTopAndStopsAtTheStripFloor() {
        val rise = 80f
        val floor = 413f
        val bitmap = Bitmap.createBitmap(240, (rise + floor + 40).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { translate(0f, rise) }
        ChartDrawing(canvas, 240f, now, now).sky(noon, 186f, 0.0, 0f, 0f, bodies = false, rise = rise, floor = floor)
        val pageTop = bitmap.getPixel(40, 1)
        val stripTop = bitmap.getPixel(40, rise.toInt() + 1)
        assertTrue("page top ${Integer.toHexString(pageTop)}", isDayBlue(pageTop))
        // One gradient spans the header and the strip, so the strip's top is already lighter than the page's.
        assertTrue(Color.green(stripTop) > Color.green(pageTop) + 10)
        assertEquals("nothing is painted below the floor", 0, Color.alpha(bitmap.getPixel(40, (rise + floor + 20).toInt())))
        bitmap.recycle()
    }

    @Test fun aMountedStripCrossesThePagePaddingAndPaintsTheSkyBehindTheHeader() {
        val fridayHarbor = StationIdentity(StationId("noaa/9449880"), "Friday Harbor", "", 48.5453, -123.0125, StationKind.Tide)
        val points = (-6..6).map { TidePoint(now.plusSeconds(it * 3600L), 1.0 + it * 0.05) }
        val series = PredictionSeries.Tide(points, points.map { TideRatePoint(it.time, 0.05) }, emptyList())
        val taps = mutableListOf<Offset>()
        compose.setContent {
            Column(Modifier.testTag("page").width(260.dp).padding(16.dp)) {
                Box(Modifier.testTag("header").height(60.dp)) { Text("Friday Harbor", color = androidx.compose.ui.graphics.Color.White) }
                TimelineCanvas(fridayHarbor, series, emptyList(), now, now, Modifier.testTag("plot"),
                    controlsVisible = false, bleed = 16.dp, pageTop = 16.dp,
                    inputModifier = { Modifier.pointerInput(Unit) { detectTapGestures { taps += it } } })
            }
        }
        // The strip takes touches in the padding it crosses, out to both edges of the page.
        compose.onNodeWithTag("page").performTouchInput { click(Offset(3f, 200.dp.toPx())) }
        compose.onNodeWithTag("page").performTouchInput { click(Offset(width - 3f, 200.dp.toPx())) }
        compose.runOnIdle {
            assertEquals(2, taps.size)
            assertEquals(3f, taps[0].x, 1f)
            assertEquals(with(compose.density) { 260.dp.toPx() } - 3f, taps[1].x, 1f)
        }
        compose.onNodeWithTag("plot").assertWidthIsEqualTo(260.dp)
        assertEquals(0.dp, compose.onNodeWithTag("plot").getUnclippedBoundsInRoot().left)
        // Building the sky scene searches for eclipses, which a cold emulator can take most of a minute to do.
        compose.waitUntil(60_000) {
            val corner = compose.onNodeWithTag("page").captureToImage().toPixelMap()[4, 4]
            corner.blue > 0.4f && corner.blue > corner.red + 0.25f
        }
        // The header draws over the sky: its white title is still on the page.
        val header = compose.onNodeWithTag("header").captureToImage().toPixelMap()
        assertTrue((0 until header.width).any { x -> (0 until header.height).any { y ->
            val pixel = header[x, y]
            pixel.red > 0.9f && pixel.green > 0.9f && pixel.blue > 0.9f
        } })
    }
}
