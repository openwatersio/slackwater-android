package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CalendarEventKind(val key: String) {
    HIGH_TIDE("high_tide"), LOW_TIDE("low_tide"), SLACK("slack"), SLACK_WINDOW("slack_window"), LUNAR_ECLIPSE("lunar_eclipse"),
}

/** The provider uses stationId and start together to open the station at this event. */
data class StationCalendarEvent(
    val stationId: StationId, val title: String, val start: Instant, val end: Instant,
    val notes: String, val zone: ZoneId, val kind: CalendarEventKind,
    val heightM: Double? = null, val imperial: Boolean = false, val thresholdKnots: Double? = null,
    val windowEnd: Instant? = null,
)

fun stationCalendarTitle(station: StationIdentity): String? = when (station.series) {
    StationSeries.Tide -> "${station.name} Tides"
    StationSeries.Current -> "${station.name} Currents"
    null -> null
}

/** Null means the current source cannot supply a complete plan; preserve existing entries until it recovers. */
suspend fun stationCalendarEvents(
    station: StationIdentity, now: Instant, thresholdKnots: Double, imperial: Boolean, repository: PredictionRepository,
): List<StationCalendarEvent>? {
    val prediction = stationCalendarPrediction(station, now, repository)
    return stationCalendarEvents(station, now, thresholdKnots, imperial, prediction)
}

private fun calendarTriggers(kind: StationKind): List<AlertTrigger> = when (kind) {
    StationKind.Tide, StationKind.CanadianTide -> listOf(AlertTrigger.TideExtreme(true), AlertTrigger.TideExtreme(false))
    StationKind.Current, StationKind.CanadianCurrent -> listOf(AlertTrigger.SlackWindowOpens)
    StationKind.DerivedGate -> listOf(AlertTrigger.Slack)
    StationKind.Restricted, StationKind.Retired -> emptyList()
}

private fun StationIdentity.calendarZone() = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

suspend fun stationCalendarPrediction(station: StationIdentity, now: Instant, repository: PredictionRepository): PredictionResult {
    val first = calendarTriggers(station.kind).firstOrNull()
        ?: return PredictionResult.Unavailable(PredictionResult.Reason.UNSUPPORTED_PURPOSE)
    val zone = station.calendarZone()
    val span = OccurrenceResolver.querySpan(first, TimeSpan(now, now.plus(CALENDAR_HORIZON)), zone)
    return repository.load(station.id, span, PredictionPurpose.CALENDAR)
}

fun PredictionResult.coversCalendar(now: Instant): Boolean = this is PredictionResult.Ready &&
    capabilities.supports(PredictionPurpose.CALENDAR) && coverage.start <= now && coverage.end >= now.plus(CALENDAR_HORIZON)

fun stationCalendarEvents(
    station: StationIdentity, now: Instant, thresholdKnots: Double, imperial: Boolean, prediction: PredictionResult,
): List<StationCalendarEvent>? {
    require(thresholdKnots.isFinite() && thresholdKnots >= 0)
    if (prediction !is PredictionResult.Ready || !prediction.coversCalendar(now)) return null
    val ready = prediction
    val horizon = TimeSpan(now, now.plus(CALENDAR_HORIZON))
    val triggers = calendarTriggers(station.kind)
    val zone = station.calendarZone()
    val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone)
    return (triggers + AlertTrigger.Eclipse).flatMap { trigger ->
        val rule = AlertRule(stationId = station.id, trigger = trigger)
        OccurrenceResolver.occurrences(rule, listOf(ready), zone, station.latitude, station.longitude, thresholdKnots)
            .filter { it.event > now && it.event <= horizon.end }
            .map { calendarEvent(station.id, trigger, it, thresholdKnots, imperial, zone, clock) }
    }.distinct().sortedBy { it.start }
}

private fun calendarEvent(
    station: StationId, trigger: AlertTrigger, occurrence: AlertOccurrence,
    threshold: Double, imperial: Boolean, zone: ZoneId, clock: DateTimeFormatter,
): StationCalendarEvent {
    val start = clock.format(occurrence.event)
    val speed = "%.1f kn".format(Locale.ROOT, threshold)
    val (kind, title, notes) = when (trigger) {
        AlertTrigger.SlackWindowOpens -> when {
            occurrence.noWindow && threshold == 0.0 -> Triple(CalendarEventKind.SLACK, "Slack", start)
            occurrence.noWindow -> Triple(CalendarEventKind.SLACK, "Slack", "$start, no window under $speed")
            occurrence.end != null -> Triple(CalendarEventKind.SLACK_WINDOW, "Slack window", "$start–${clock.format(occurrence.end)}, under $speed")
            else -> Triple(CalendarEventKind.SLACK_WINDOW, "Slack window", "$start, under $speed")
        }
        AlertTrigger.Slack -> Triple(CalendarEventKind.SLACK, "Slack", start)
        AlertTrigger.Eclipse -> Triple(CalendarEventKind.LUNAR_ECLIPSE, "Lunar eclipse", start)
        is AlertTrigger.TideExtreme -> {
            val height = requireNotNull(occurrence.heightM)
            val level = if (imperial) "%.1f ft".format(Locale.ROOT, height / 0.3048) else "%.2f m".format(Locale.ROOT, height)
            val turn = if (trigger.high) "High tide" else "Low tide"
            Triple(if (trigger.high) CalendarEventKind.HIGH_TIDE else CalendarEventKind.LOW_TIDE, "$turn $level", "$start · $level")
        }
        is AlertTrigger.CurrentPeak, is AlertTrigger.TideCrossing -> error("Unsupported calendar trigger: $trigger")
    }
    return StationCalendarEvent(station, title, occurrence.event, occurrence.end ?: occurrence.event, notes, zone,
        kind = kind, heightM = occurrence.heightM, imperial = imperial,
        thresholdKnots = threshold.takeIf { trigger == AlertTrigger.SlackWindowOpens && (!occurrence.noWindow || it > 0) },
        windowEnd = occurrence.end)
}
