package io.openwaters.slackwater.android.detail

import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.timeline.TimelineWindow
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class GateDetailStateTest {
    @Test fun validityEndsAtLastFullyCoveredLocalAnchorAcrossClockChange() {
        val zone = ZoneId.of("America/Vancouver")
        val day = LocalDate.of(2026, 10, 29)
        val window = TimelineWindow.fixed(day, zone)
        val last = day.plusWeeks(1)
        val end = TimelineWindow.fixed(last, zone).end
        assertEquals(last, coveredThrough(listOf(TimeSpan(window.start, end)), window, zone))
        assertEquals(last.minusDays(1), coveredThrough(listOf(TimeSpan(window.start, end.minusSeconds(1))), window, zone))
        val split = listOf(TimeSpan(window.start, window.start.plusSeconds(900)), TimeSpan(window.start.plusSeconds(1800), end))
        assertNull(coveredThrough(split, window, zone))
    }
}
