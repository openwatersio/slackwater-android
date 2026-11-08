package io.openwaters.slackwater.android.calendars

import android.content.Context
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.planning.CalendarEventKind
import io.openwaters.slackwater.android.core.planning.StationCalendarEvent
import io.openwaters.slackwater.android.core.station.SpeedUnitKind
import io.openwaters.slackwater.android.core.station.formatHeightValue
import io.openwaters.slackwater.android.core.station.formatSpeedValue
import io.openwaters.slackwater.android.stations.cardTimeFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun Context.localizeCalendarEvents(events: List<StationCalendarEvent>): List<StationCalendarEvent> {
    val clocks = mutableMapOf<ZoneId, DateTimeFormatter>()
    val locale = resources.configuration.locales[0]
    return events.map { event ->
        val clock = clocks.getOrPut(event.zone) { cardTimeFormatter(locale, event.zone) }
        val start = clock.format(event.start)
        val (title, notes) = when (event.kind) {
            CalendarEventKind.HIGH_TIDE, CalendarEventKind.LOW_TIDE -> {
                val level = getString(R.string.card_value_unit, formatHeightValue(requireNotNull(event.heightM), event.imperial, locale),
                    getString(if (event.imperial) R.string.unit_feet else R.string.unit_metres))
                val turn = getString(if (event.kind == CalendarEventKind.HIGH_TIDE) R.string.notification_high else R.string.notification_low)
                getString(R.string.card_value_unit, turn, level) to getString(R.string.alert_subject_time, start, level)
            }
            CalendarEventKind.LUNAR_ECLIPSE -> getString(R.string.pdf_lunar) to start
            CalendarEventKind.SLACK, CalendarEventKind.SLACK_WINDOW -> {
                val speed = event.thresholdKnots?.let {
                    getString(R.string.card_value_unit, formatSpeedValue(it, SpeedUnitKind.KNOTS, locale), getString(R.string.unit_knots))
                }
                if (event.kind == CalendarEventKind.SLACK) {
                    getString(R.string.pdf_slack) to (speed?.let { getString(R.string.calendar_no_window, start, it) } ?: start)
                } else {
                    val limit = requireNotNull(speed)
                    val description = event.windowEnd?.let { getString(R.string.calendar_window_range, start, clock.format(it), limit) }
                        ?: getString(R.string.calendar_window_start, start, limit)
                    getString(R.string.chart_window) to description
                }
            }
        }
        event.copy(title = title, notes = notes)
    }
}

internal fun Context.localizeCalendarEvent(event: StationCalendarEvent) = localizeCalendarEvents(listOf(event)).single()
