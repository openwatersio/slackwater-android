package io.openwaters.slackwater.android.premium

import android.icu.util.Calendar
import android.icu.util.TimeZone
import io.openwaters.slackwater.android.core.premium.FreeDayCalendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

internal fun ZoneId.toIcuTimeZone(): TimeZone {
    val fixed = normalized() as? ZoneOffset ?: return TimeZone.getTimeZone(id)
    return TimeZone.getTimeZone(if (fixed == ZoneOffset.UTC) "GMT" else "GMT${fixed.id}")
}

class DeviceFreeDayCalendar(private val locale: Locale = Locale.getDefault(Locale.Category.FORMAT)) : FreeDayCalendar {
    internal fun calendar(zone: ZoneId): Calendar = Calendar.getInstance(zone.toIcuTimeZone(), locale)

    fun monthStart(date: Instant, zone: ZoneId): Instant = monthBoundary(date, zone, 0)

    override fun nextMonthStart(date: Instant, zone: ZoneId): Instant = monthBoundary(date, zone, 1)

    private fun monthBoundary(date: Instant, zone: ZoneId, monthsAhead: Int): Instant {
        val calendar = calendar(zone)
        calendar.timeInMillis = date.toEpochMilli()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.add(Calendar.MONTH, monthsAhead)
        // ICU identifies the calendar date. atStartOfDay resolves midnight gaps and overlaps in the device zone.
        val localDate = LocalDate.ofEpochDay(calendar.get(Calendar.JULIAN_DAY) - JULIAN_DAY_AT_UNIX_EPOCH)
        return localDate.atStartOfDay(zone).toInstant()
    }

    private companion object {
        const val JULIAN_DAY_AT_UNIX_EPOCH = 2_440_588L
    }
}
