package io.openwaters.slackwater.android.widgets

import io.openwaters.slackwater.android.core.station.CardCurve
import io.openwaters.slackwater.android.core.station.CurveRun
import io.openwaters.slackwater.android.core.station.CurveSample
import io.openwaters.slackwater.android.core.station.cardSpan
import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class WidgetSnapshotTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")

    @Test fun eachSizePlacesNowOnItsSpecifiedSwing() {
        val small = widgetSpan(now, false)
        val wide = widgetSpan(now, true)
        assertEquals(cardSpan(now), wide)
        assertEquals(Duration.between(small.start, now).multipliedBy(3), Duration.between(now, small.end))
        assertEquals(Duration.between(small.start, small.end).multipliedBy(2), Duration.between(wide.start, wide.end))
    }

    @Test fun combinedVariantsFitTheBitmapBudgetOnSmallScreensAndTallTablets() {
        for ((screenWidth, screenHeight) in listOf(160 to 240, 3200 to 2400)) {
            val sizes = List(16) { (233 + it * 65) to (110 + it * 13) }
            val pixels = sizes.sumOf { (width, height) ->
                val scale = widgetBitmapScale(width, height, 4f, screenWidth, screenHeight, sizes.size)
                (width * scale).toInt() * (height * scale).toInt()
            }
            assertTrue(pixels * 4 <= 512 * 1024)
            assertTrue(pixels <= screenWidth * screenHeight)
        }
    }

    @Test fun sameWidthClassRetainsBothOrientationDimensionsAndExactLauncherSizes() {
        val orientations = orientationWidgetSizes(300, 110, 425, 230)
        assertEquals(WidgetSize(300f, 230f), orientations.first)
        assertEquals(WidgetSize(425f, 110f), orientations.second)
        assertEquals(orientations.toList(), launcherWidgetSizes(emptyList(), orientations))
        val exact = listOf(WidgetSize(280.5f, 130f), WidgetSize(280.5f, 220f), WidgetSize(211f, 310f))
        assertEquals(exact, launcherWidgetSizes(exact + exact.first(), orientations))
        assertTrue(exact.first().wide)
        assertFalse(exact.last().wide)
        assertEquals(orientations.toList(), launcherWidgetSizes(List(17) { WidgetSize(it + 100f, 110f) }, orientations))
    }

    @Test fun absoluteClosingRequiresARealEdgeInsideTwoHours() {
        fun closing(close: Long, last: Long = 9000, from: Long = -60): Instant? {
            val end = now.plusSeconds(last)
            return CardCurve(now.minusSeconds(300), end, now,
                listOf(CurveSample(now.minusSeconds(300), 1.0), CurveSample(end, 2.0)), emptyList(), true,
                windows = listOf(CurveRun(now.plusSeconds(from), now.plusSeconds(close)))).windowClosing(now)
        }
        assertEquals(now.plusSeconds(600), closing(600))
        assertNull(closing(600, last = 600))
        assertNull(closing(7200))
        assertNull(closing(1, from = 1))
        assertNull(closing(0))
    }
}
