package io.openwaters.slackwater.android.core.premium

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class FreeDayRecord(val purchased: Boolean, val started: Instant?, val historyReadable: Boolean = true)

sealed interface FreeDayStatus {
    data object Purchased : FreeDayStatus
    data object Unavailable : FreeDayStatus
    data object Available : FreeDayStatus
    data class Active(val ends: Instant) : FreeDayStatus
    data class Used(val next: Instant) : FreeDayStatus
}

fun interface FreeDayCalendar {
    fun nextMonthStart(date: Instant, zone: ZoneId): Instant
}

private val isoCalendar = FreeDayCalendar { date, zone ->
    YearMonth.from(date.atZone(zone)).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
}

fun FreeDayRecord.status(now: Instant, zone: ZoneId, calendar: FreeDayCalendar = isoCalendar): FreeDayStatus {
    if (purchased) return FreeDayStatus.Purchased
    if (!historyReadable) return FreeDayStatus.Unavailable
    val start = started ?: return FreeDayStatus.Available
    val end = start.plus(1, ChronoUnit.DAYS)
    if (now >= start && now < end) return FreeDayStatus.Active(end)

    val next = calendar.nextMonthStart(start, zone)
    if (calendar.nextMonthStart(now, zone) != next) return FreeDayStatus.Available
    return FreeDayStatus.Used(next)
}
