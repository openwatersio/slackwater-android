package io.openwaters.slackwater.android.detail

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SummarySelectionTest {
    private val start = Instant.parse("2026-11-01T08:00:00Z")

    @Test fun rangeFollowsTheScrubAcrossAnExtreme() {
        val tide = PredictionSeries.Tide(emptyList(), emptyList(), listOf(
            TideExtreme(start, -1.0, ExtremeKind.LOW),
            TideExtreme(start.plusSeconds(21600), 2.0, ExtremeKind.HIGH),
            TideExtreme(start.plusSeconds(43200), 0.0, ExtremeKind.LOW),
        ))
        val marks = tide.waterMarks()
        assertEquals(WaterMark(start.plusSeconds(21600), 3.0, true, start, 2.0), marks.first())
        assertEquals(WaterMark(start.plusSeconds(43200), 2.0, false, start.plusSeconds(21600), 0.0), marks.last())
    }

    @Test fun maximaRetainTheirDirectionAndDerivedTimingHasNoSpeed() {
        val events = listOf(CurrentEvent(start, -2.0, CurrentEventKind.MAX_EBB),
            CurrentEvent(start.plusSeconds(3600), 0.0, CurrentEventKind.SLACK),
            CurrentEvent(start.plusSeconds(7200), 1.0, CurrentEventKind.MAX_FLOOD))
        val marks = PredictionSeries.Current(emptyList(), events, null, null).waterMarks()
        assertEquals(listOf(false, true), marks.map { it.rising })
        assertEquals(listOf(2.0, 1.0), marks.map { it.value })
        assertTrue(PredictionSeries.Derived(listOf(PredictionSeries.DerivedSlack(start, ExtremeKind.HIGH))).waterMarks().isEmpty())
    }

    @Test fun phaseWindowsIncludeBothSidesOfNewMoonAndDistinguishQuarters() {
        assertEquals(0, phaseIndex(.99))
        assertEquals(0, phaseIndex(.01))
        assertEquals(2, phaseIndex(.25))
        assertEquals(4, phaseIndex(.5))
        assertEquals(6, phaseIndex(.75))
        assertEquals(7, phaseIndex(.88))
    }
}
