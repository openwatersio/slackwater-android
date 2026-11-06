package io.openwaters.slackwater.android.detail

import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.timeline.ChartScene
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ChartGeometryTest {
    @Test
    fun widthRevealsMoreWaterAndComfortDoesNotMoveTheZeroLineOrChangeCurrentMagnitude() {
        val now = Instant.parse("2026-11-01T09:00:00Z")
        val points = (-30..30).map { hour ->
            CurrentPoint(
                now.plusSeconds(hour * 3600L), when (hour) {
                    23 -> 10.0; 12 -> 5.0; else -> 1.0
                }
            )
        }
        val series = PredictionSeries.Current(points, emptyList(), 0.0, 180.0)
        val scene = ChartScene(series, emptyList(), 0.5, false)
        val phone = scene.fit(now, 360f, false)
        val tablet = scene.fit(now, 840f, false)
        assertEquals(1.05, phone.half.toDouble(), 0.0001)
        assertEquals(10.5, tablet.half.toDouble(), 0.0001)
        assertEquals(245f, tablet.y(0.0), 0f)
        assertEquals(245f - tablet.y(2.0), tablet.y(-2.0) - 245f, 0.0001f)
        assertEquals(phone, ChartScene(series, emptyList(), 4.0, false).fit(now, 360f, false))
        assertEquals(tablet, scene.fit(now, 360f, true))
    }

    @Test
    fun heldScaleExpandsAtTheOuterEdgeAndContractsOnlyBelowTheGovernorFloor() {
        val held = ChartScale(0f, 10f)
        assertTrue(held.shouldAdopt(ChartScale(0f, 10.3f), 1.05))
        assertFalse(held.shouldAdopt(ChartScale(0f, 8f), 1.05))
        assertTrue(held.shouldAdopt(ChartScale(0f, 6.49f), 1.05))
        assertFalse(held.shouldAdopt(ChartScale(0f, 6.50f), 1.05))
    }
}
