package io.openwaters.slackwater.android.core.timeline

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TimelineWindowTest {
    @Test fun fixedOnlineExtentUsesElapsedHoursThroughBothClockChanges() {
        val zone = ZoneId.of("America/Vancouver")
        for (anchor in listOf(LocalDate.parse("2026-03-08"), LocalDate.parse("2025-11-02"))) {
            val midnight = anchor.atStartOfDay(zone).toInstant()
            val window = TimelineWindow.fixed(anchor, zone)
            assertEquals(midnight.minus(Duration.ofHours(48)), window.start)
            assertEquals(midnight.plus(Duration.ofHours(180)), window.end)
            assertEquals(4104.0, window.width, 0.0)
            val exact = midnight.plusSeconds(123).plusNanos(450000000)
            assertEquals(exact, window.time(window.x(exact)))
            assertEquals(exact, window.selected(window.offset(exact, 390.0), 390.0))
            assertEquals(window.start.plusSeconds(39000), window.clamp(window.start.minusSeconds(1), 390.0))
            assertEquals(window.time(window.width / 2), window.clamp(midnight, 5000.0))
        }
    }

    @Test fun offsetCompensationKeepsTheInstantUnderTheReader() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val original = TimelineWindow(start, start.plus(Duration.ofDays(7)))
        val expanded = original.copy(start = start.minus(Duration.ofDays(7)))
        val time = start.plus(Duration.ofDays(3))
        val compensated = original.offset(time, 390.0) + expanded.x(start)
        assertEquals(time, expanded.selected(compensated, 390.0))
    }
}
