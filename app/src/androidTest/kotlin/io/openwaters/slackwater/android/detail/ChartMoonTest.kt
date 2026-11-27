package io.openwaters.slackwater.android.detail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.openwaters.slackwater.android.core.timeline.SkyBody
import io.openwaters.slackwater.android.core.timeline.SkyFrame
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

/** The moon's lit face is one smooth shape out to the edge of its 20-unit disc (scrubber §14). */
class ChartMoonTest {
    private val now = Instant.parse("2026-06-01T08:00:00Z")
    private val skyWidth = 240f
    private val horizon = 186f
    private val scale = 8

    // Night, with the moon held at the centre of the sky and the sun far below the horizon.
    private fun frame(phase: Double, limbDegrees: Double = 0.0) = SkyFrame(
        sun = SkyBody(x = 0.9, altitude = -30.0), moon = SkyBody(x = 0.5, altitude = 30.0),
        phase = phase, limbDegrees = limbDegrees, obscuration = 0.0, umbra = 0.0, wash = 0.0, stars = emptyList(),
    )

    private fun draw(frame: SkyFrame): Bitmap {
        val bitmap = Bitmap.createBitmap((skyWidth * scale).toInt(), 200 * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(scale.toFloat(), scale.toFloat()) }
        ChartDrawing(canvas, skyWidth, now, now).sky(frame, horizon, 0.0, 0f, 0f)
        return bitmap
    }

    private fun Bitmap.at(dx: Float, dy: Float) =
        getPixel(((skyWidth / 2 + dx) * scale).toInt(), ((horizon - 90 + dy) * scale).toInt())

    private fun lit(color: Int) = Color.red(color) > 200 && Color.green(color) > 200

    @Test fun aFullMoonIsLitOutToEveryEdgeOfItsDisc() {
        val moon = draw(frame(phase = 1.0))
        // Just inside the 10-unit radius, around the whole limb.
        for (degrees in 0 until 360 step 15) {
            val angle = Math.toRadians(degrees.toDouble())
            val pixel = moon.at((9.6 * Math.cos(angle)).toFloat(), (9.6 * Math.sin(angle)).toFloat())
            assertTrue("at $degrees° ${Integer.toHexString(pixel)}", lit(pixel))
        }
        moon.recycle()
    }

    @Test fun theLitFaceIsEvenAndStaysInsideTheDisc() {
        val moon = draw(frame(phase = 1.0, limbDegrees = 40.0))
        val centre = moon.at(0f, 0f)
        // No stripes: every pixel across the face matches the centre.
        for (step in -70..70) {
            val pixel = moon.at(step / 10f, step / 10f * 0.3f)
            assertTrue("at $step ${Integer.toHexString(pixel)}", Math.abs(Color.red(pixel) - Color.red(centre)) < 6)
        }
        // Nothing lit past the limb.
        for (degrees in 0 until 360 step 15) {
            val angle = Math.toRadians(degrees.toDouble())
            val pixel = moon.at((10.5 * Math.cos(angle)).toFloat(), (10.5 * Math.sin(angle)).toFloat())
            assertTrue("at $degrees° ${Integer.toHexString(pixel)}", Color.red(pixel) < Color.red(centre) - 20)
        }
        moon.recycle()
    }

    @Test fun aNewMoonLeavesNoLitRimOnItsLimb() {
        val moon = draw(frame(phase = 0.0))
        for (degrees in -90..90 step 5) {
            val angle = Math.toRadians(degrees.toDouble())
            for (radius in listOf(9.6, 9.9, 10.0)) {
                val pixel = moon.at((radius * Math.cos(angle)).toFloat(), (radius * Math.sin(angle)).toFloat())
                assertTrue("at $degrees° $radius ${Integer.toHexString(pixel)}", !lit(pixel))
            }
        }
        moon.recycle()
    }

    @Test fun aHalfMoonLightsOnlyItsBrightSide() {
        val moon = draw(frame(phase = 0.5))
        assertTrue(lit(moon.at(5f, 0f)))
        assertTrue(lit(moon.at(2f, -9f)))
        assertTrue(!lit(moon.at(-5f, 0f)))
        moon.recycle()
    }
}
