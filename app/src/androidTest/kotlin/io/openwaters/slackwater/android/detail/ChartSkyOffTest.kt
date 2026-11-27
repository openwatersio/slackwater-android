package io.openwaters.slackwater.android.detail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
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
import io.openwaters.slackwater.android.core.timeline.SkyStar
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChartSkyOffTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-06-01T20:00:00Z")
    private val width = 240f
    private val horizon = 186f
    // Midday: the sun high in the middle of the sky, the moon up to its left.
    private val noon = SkyFrame(
        sun = SkyBody(x = 0.5, altitude = 30.0), moon = SkyBody(x = 0.25, altitude = 30.0),
        phase = 0.5, limbDegrees = 0.0, obscuration = 0.0, umbra = 0.0, wash = 0.0, stars = emptyList(),
    )

    private fun draw(frame: SkyFrame?, bodies: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap((width * 4).toInt(), 420 * 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(4f, 4f) }
        ChartDrawing(canvas, width, now, now).sky(frame, horizon, 0.0, 0f, 0f, bodies = bodies)
        return bitmap
    }

    // Where `sky` places the sun's 8-unit disc: centred across the padded width, 3 units per degree above the horizon.
    private fun sunPixel(bitmap: Bitmap) =
        bitmap.getPixel(((noon.sun.x * (width + 16) - 8) * 4).toInt(), ((horizon - 3 * noon.sun.altitude) * 4).toInt())

    private fun isSunYellow(color: Int) = Color.red(color) > 200 && Color.green(color) > 160 && Color.blue(color) < 140

    @Test fun withTheBodiesOffTheDaySkyKeepsItsGradientWithoutTheSun() {
        val bare = draw(null, bodies = false)
        val navy = bare.getPixel(40, 20)
        bare.recycle()
        val off = draw(noon, bodies = false)
        // The daytime top colour is a day blue, well away from the navy drawn when no sky is painted.
        val top = off.getPixel(40, 20)
        assertTrue("top ${Integer.toHexString(top)} navy ${Integer.toHexString(navy)}",
            Color.blue(top) - Color.blue(navy) > 60 && Color.blue(top) > Color.red(top) + 60)
        // The gradient brightens toward the horizon.
        val low = off.getPixel(40, ((horizon - 4) * 4).toInt())
        assertTrue(Color.red(low) + Color.green(low) + Color.blue(low) > Color.red(top) + Color.green(top) + Color.blue(top))
        assertFalse("sun ${Integer.toHexString(sunPixel(off))}", isSunYellow(sunPixel(off)))
        off.recycle()

        val on = draw(noon, bodies = true)
        assertTrue("sun ${Integer.toHexString(sunPixel(on))}", isSunYellow(sunPixel(on)))
        on.recycle()
    }

    @Test fun withTheBodiesOffANightSkyDrawsNoStarsOrMoonAndAnEclipseStillDarkensIt() {
        // Night: the sun well below the horizon, a bright star high on the right and the moon up on the left.
        val night = noon.copy(sun = SkyBody(x = 0.5, altitude = -30.0),
            stars = listOf(SkyStar(SkyBody(x = 0.8, altitude = 40.0), magnitude = 0.0)))
        val starAt = (night.stars.single().body.x * width).toFloat() to horizon - 3 * 40f
        val moonAt = (night.moon.x * (width + 22) - 11).toFloat() to horizon - 3 * 30f
        // The gradient is vertical, so a pixel 40 units to the side at the same height shows the bare sky.
        fun stands(bitmap: Bitmap, at: Pair<Float, Float>): Boolean {
            val mark = bitmap.getPixel((at.first * 4).toInt(), (at.second * 4).toInt())
            val bare = bitmap.getPixel(((at.first - 40) * 4).toInt(), (at.second * 4).toInt())
            return listOf(Color::red, Color::green, Color::blue).any { it(mark) - it(bare) > 24 }
        }
        val on = draw(night, bodies = true)
        assertTrue("star with bodies on", stands(on, starAt))
        assertTrue("moon with bodies on", stands(on, moonAt))
        on.recycle()
        val off = draw(night, bodies = false)
        assertFalse("star with bodies off", stands(off, starAt))
        assertFalse("moon with bodies off", stands(off, moonAt))
        off.recycle()

        val clear = draw(noon, bodies = false)
        val eclipsed = draw(noon.copy(obscuration = 0.98), bodies = false)
        fun brightness(color: Int) = Color.red(color) + Color.green(color) + Color.blue(color)
        assertTrue("an eclipse darkens the day sky", brightness(eclipsed.getPixel(40, 20)) < brightness(clear.getPixel(40, 20)) - 30)
        clear.recycle(); eclipsed.recycle()
    }

    // The mounted chart must still compute sky frames with the switch off, or it can only paint navy.
    @Test fun aMountedChartWithTheSkyOffPaintsTheDaySky() {
        val fridayHarbor = StationIdentity(StationId("noaa/9449880"), "Friday Harbor", "", 48.5453, -123.0125, StationKind.Tide)
        val points = (-6..6).map { TidePoint(now.plusSeconds(it * 3600L), 1.0 + it * 0.05) }
        val series = PredictionSeries.Tide(points, points.map { TideRatePoint(it.time, 0.05) }, emptyList())
        compose.setContent {
            Box(Modifier.width(260.dp)) {
                TimelineCanvas(fridayHarbor, series, emptyList(), now, now, Modifier.testTag("skyOffPlot"),
                    showSky = false, controlsVisible = false)
            }
        }
        // 20:00 UTC is just past Friday Harbor's midday, so the sky's top is a day blue once a frame arrives.
        // Building the sky scene searches for eclipses, which a cold API 26 emulator can take most of a minute to do.
        compose.waitUntil(60_000) {
            val corner = compose.onNodeWithTag("skyOffPlot").captureToImage().toPixelMap()[4, 4]
            corner.blue > 0.4f && corner.blue > corner.red + 0.25f
        }
    }
}
