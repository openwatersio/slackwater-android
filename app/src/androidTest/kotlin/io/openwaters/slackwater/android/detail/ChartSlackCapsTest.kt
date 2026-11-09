package io.openwaters.slackwater.android.detail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import io.openwaters.slackwater.CurrentEvent
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.station.SpeedUnitKind
import io.openwaters.slackwater.android.core.timeline.ChartScene
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSlackCapsTest {
    private val slack = Instant.parse("2026-06-01T12:00:00Z")
    private val selected = slack.minusSeconds(7200)
    private val sky = Color.rgb(87, 71, 105)
    private val fill = Color.rgb(5, 18, 42)
    private val yellow = Color.rgb(245, 201, 107)
    private val blue = Color.rgb(56, 189, 248)
    private val green = Color.rgb(136, 184, 104)

    @Test fun onlyOuterCapsBreakTheStrokeInFutureAndPast() {
        val series = PredictionSeries.Current(
            listOf(-1, 0, 1).map { CurrentPoint(slack.plusSeconds(it * 7200L), it * 0.4) },
            listOf(CurrentEvent(slack, 0.0, CurrentEventKind.SLACK)), null, null,
        )
        for (window in listOf(false, true)) {
            val nowOffsets = if (window) listOf(-72.0, 10.5, 72.0) else listOf(-72.0, 72.0)
            for (nowOffset in nowOffsets) {
                val scene = ChartScene(series, emptyList(), if (window) 0.2 else 0.0, false)
                val bitmap = Bitmap.createBitmap(240 * 4, 420 * 4, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(sky)
                val canvas = Canvas(bitmap).apply { scale(4f, 4f) }
                val now = slack.plusSeconds((nowOffset * 200).toLong())
                // Keep the curve on a single raster row, clear of the dashed zero line.
                val scale = ChartScale(3f, 1f)
                ChartDrawing(canvas, 240f, selected, now).plot(scene, scale, emptyList(), ZoneOffset.UTC,
                    Locale.US, DateTimeFormatter.ofPattern("HH:mm"), false, SpeedUnitKind.KNOTS,
                    false, emptyList(), emptyList(), false, false, false, emptyList())

                // 200 seconds per dp puts slack at x=156. Green ends at ±3 dp; the window ends at ±18 dp.
                fun checkStroke(offset: Double, ink: Int?) {
                    for ((y, background) in listOf(316.5 to sky, 317.5 to fill)) {
                        val actual = bitmap.getPixel(((156 + offset) * 4).toInt(), (y * 4).toInt())
                        val expected = if (ink == null) background else ColorUtils.compositeColors(
                            ColorUtils.setAlphaComponent(ink, if (offset < nowOffset) 89 else 255), background)
                        assertTrue("nowOffset=$nowOffset window=$window x=$offset y=$y expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}",
                            listOf(Color::red, Color::green, Color::blue).all { abs(it(actual) - it(expected)) <= 3 })
                    }
                }
                for (side in listOf(-1, 1)) {
                    checkStroke(side * 2.5, green)
                    if (window) {
                        checkStroke(side * 3.5, blue)
                        checkStroke(side * 17.5, blue)
                    }
                    val edge = if (window) 18.0 else 3.0
                    checkStroke(side * (edge + 0.5), null)
                    checkStroke(side * (edge + 2.0), null)
                    checkStroke(side * (edge + 3.0), yellow)
                }
                bitmap.recycle()
            }
        }
    }
}
