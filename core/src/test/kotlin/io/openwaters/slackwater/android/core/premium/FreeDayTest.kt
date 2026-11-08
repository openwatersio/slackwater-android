package io.openwaters.slackwater.android.core.premium

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeDayTest {
    private val seattle = ZoneId.of("America/Los_Angeles")
    private val november = Instant.parse("2026-11-05T18:00:00Z")
    private val december = Instant.parse("2026-12-01T08:00:00Z")

    @Test fun purchaseTakesPrecedenceAndANeverUsedDayIsAvailable() {
        assertEquals(FreeDayStatus.Available, FreeDayRecord(false, null).status(november, seattle))
        for (start in listOf(null, november, november.minusSeconds(86400))) {
            assertEquals(FreeDayStatus.Purchased, FreeDayRecord(true, start).status(november, seattle))
        }
    }

    @Test fun elapsedDayEndsAtExactly24HoursAcrossFallClockChangeAndLocalMonthBoundary() {
        val start = Instant.parse("2026-11-01T06:00:00Z") // October 31, 23:00 in Seattle.
        val end = Instant.parse("2026-11-02T06:00:00Z")
        val record = FreeDayRecord(false, start)
        for (now in listOf(start, Instant.parse("2026-11-01T08:30:00Z"), end.minusNanos(1))) {
            assertEquals(FreeDayStatus.Active(end), record.status(now, seattle))
        }
        assertEquals(FreeDayStatus.Available, record.status(end, seattle))
        assertEquals(FreeDayStatus.Used(Instant.parse("2026-12-01T00:00:00Z")),
            record.status(end, ZoneId.of("UTC")))
    }

    @Test fun aClaimJustAfterLocalMidnightCountsUntilNextLocalMonth() {
        val record = FreeDayRecord(false, Instant.parse("2026-11-01T07:30:00Z"))
        assertEquals(FreeDayStatus.Used(december), record.status(december.minusNanos(1), seattle))
        assertEquals(FreeDayStatus.Available, record.status(december, seattle))
    }

    @Test fun aFutureStartBlocksOnlyItsOwnCalendarMonth() {
        val sameMonth = FreeDayRecord(false, Instant.parse("2026-11-25T18:00:00Z"))
        val laterMonth = FreeDayRecord(false, Instant.parse("2026-12-10T18:00:00Z"))
        val laterYear = FreeDayRecord(false, Instant.parse("2027-11-05T18:00:00Z"))
        assertEquals(FreeDayStatus.Used(december), sameMonth.status(november, seattle))
        assertEquals(FreeDayStatus.Available, laterMonth.status(november, seattle))
        assertEquals(FreeDayStatus.Available, laterYear.status(november, seattle))
    }
}
