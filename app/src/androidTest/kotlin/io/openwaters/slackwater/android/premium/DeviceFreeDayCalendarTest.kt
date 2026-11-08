package io.openwaters.slackwater.android.premium

import io.openwaters.slackwater.android.core.premium.FreeDayRecord
import io.openwaters.slackwater.android.core.premium.FreeDayStatus
import io.openwaters.slackwater.android.core.premium.status
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceFreeDayCalendarTest {
    @Test fun halfHourOffsetSelectsTheNewMonthBeforeUtcMidnight() {
        val calendar = DeviceFreeDayCalendar(Locale.US)
        val zone = ZoneOffset.of("+05:30")
        val boundary = Instant.parse("2024-03-31T18:30:00Z")
        assertEquals(boundary, calendar.nextMonthStart(boundary.minusMillis(1), zone))
        assertEquals(boundary, calendar.monthStart(boundary, zone))
    }

    @Test fun midnightOverlapStartsMonthAtTheFirstOccurrence() {
        val calendar = DeviceFreeDayCalendar(Locale.US)
        val zone = ZoneId.of("America/Havana")
        val firstMidnight = Instant.parse("2020-11-01T04:00:00Z")
        assertEquals(firstMidnight, calendar.nextMonthStart(firstMidnight.minusMillis(1), zone))
        assertEquals(firstMidnight, calendar.monthStart(firstMidnight.plusSeconds(3600), zone))
    }

    @Test fun persianNewYearResetsTheClaimAtLocalMidnightInMarch() {
        val calendar = DeviceFreeDayCalendar(Locale.forLanguageTag("fa-IR-u-ca-persian"))
        val zone = ZoneId.of("Asia/Tehran")
        val record = FreeDayRecord(false, Instant.parse("2024-03-16T12:00:00Z"))
        val newYear = Instant.parse("2024-03-19T20:30:00Z")
        assertEquals(newYear, calendar.monthStart(newYear.plusSeconds(86400), zone))
        assertEquals(FreeDayStatus.Used(newYear), record.status(newYear.minusMillis(1), zone, calendar))
        assertEquals(FreeDayStatus.Available, record.status(newYear, zone, calendar))
    }
    @Test fun prefixedFixedOffsetAndRawOffsetUseTheSameLocalDate() {
        val calendar = DeviceFreeDayCalendar(Locale.US)
        val first = Instant.parse("2024-04-30T18:30:00Z")
        assertEquals(first, calendar.monthStart(first, ZoneId.of("UTC+05:30")))
        assertEquals(first, calendar.monthStart(first, ZoneId.of("+05:30")))
    }
}
