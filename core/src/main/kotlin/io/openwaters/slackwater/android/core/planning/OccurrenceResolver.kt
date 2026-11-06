package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.ExtremeKind
import io.openwaters.slackwater.slackRuns
import io.openwaters.slackwater.android.core.prediction.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToLong

data class AlertOccurrence(
    val ruleId: UUID,
    val event: Instant,
    val fire: Instant,
    val end: Instant? = null,
    val noWindow: Boolean = false,
    val heightM: Double? = null,
    /** Original lead time when a save made this reminder immediately due. */
    val originalFire: Instant? = null,
) {
    val atEventTime: Boolean get() = (originalFire ?: fire) == event

    /** A zero-lead alert names the whole event minute. An advance reminder ends at the event instant. */
    fun isDeliverableAt(now: Instant): Boolean =
        if (atEventTime) now.truncatedTo(ChronoUnit.MINUTES) <= event else now <= event

    val key: String get() = "${ruleId}.${event.epochSecond}"
}

object OccurrenceResolver {
    private val padding = Duration.ofHours(6)

    private fun grid(time: Instant) = Instant.ofEpochSecond(Math.floorDiv(time.epochSecond, 600L) * 600)

    internal fun querySpan(trigger: AlertTrigger, span: TimeSpan, zone: ZoneId): TimeSpan = when {
        trigger is AlertTrigger.TideExtreme && trigger.ofDay -> TimeSpan(
            span.start.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(),
            span.end.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant(),
        )
        trigger is AlertTrigger.TideCrossing -> TimeSpan(grid(span.start), grid(span.end).plusSeconds(600))
        trigger == AlertTrigger.SlackWindowOpens -> TimeSpan(grid(span.start).minus(padding), span.end.plus(padding))
        else -> span
    }

    suspend fun resolve(
        rule: AlertRule, repository: PredictionRepository, span: TimeSpan, zone: ZoneId,
        latitude: Double, longitude: Double, thresholdKnots: Double,
    ): List<AlertOccurrence> {
        if (!rule.enabled || rule.heightNeedsReview) return emptyList()
        require(thresholdKnots.isFinite() && thresholdKnots >= 0)
        val pressed = rule.once?.truncatedTo(ChronoUnit.MINUTES)
        // A one-off belongs to its nearest event even when reconciliation starts after that event.
        val candidates = pressed?.let { TimeSpan(it.minus(padding), it.plus(padding)) } ?: span
        if (candidates.end < span.start || candidates.start > span.end) return emptyList()
        val search = if (pressed == null) candidates else TimeSpan(candidates.start, candidates.end.plusSeconds(60))
        val query = querySpan(rule.trigger, search, zone)
        val result = repository.load(rule.stationId, query, PredictionPurpose.ALERT)
        val blocks = when (result) {
            is PredictionResult.Ready -> listOf(result)
            is PredictionResult.Disjoint -> result.blocks
            is PredictionResult.Unavailable -> return emptyList()
        }.filter { it.capabilities.supports(PredictionPurpose.ALERT) }

        val found = occurrences(rule, blocks, zone, latitude, longitude, thresholdKnots)
        val events = found.filter { it.event in candidates }.distinctBy { it.key }.sortedBy { it.event }
        val selected = if (pressed == null) events else {
            val nearest = events.minByOrNull { Duration.between(pressed, it.event).abs() }
            listOfNotNull(nearest?.takeIf { it.event in span })
        }
        val sun = rule.sun ?: return selected
        val windows = SunWindows.spans(sun, span, latitude, longitude)
        return selected.filter { occurrence -> windows.any { occurrence.event in it } }
    }

    internal fun occurrences(
        rule: AlertRule, blocks: List<PredictionResult.Ready>, zone: ZoneId,
        latitude: Double, longitude: Double, thresholdKnots: Double,
    ): List<AlertOccurrence> {
        fun occurrence(time: Instant, end: Instant? = null, noWindow: Boolean = false, height: Double? = null): AlertOccurrence {
            val minute = time.truncatedTo(ChronoUnit.MINUTES)
            return AlertOccurrence(rule.id, minute, minute.minusSeconds(rule.leadSeconds),
                end?.truncatedTo(ChronoUnit.MINUTES), noWindow, height)
        }

        val found = mutableListOf<AlertOccurrence>()
        for (block in blocks) {
            val series = block.series
            when (val trigger = rule.trigger) {
                AlertTrigger.SlackWindowOpens -> if (series is PredictionSeries.Current) {
                    val first = series.points.firstOrNull()?.time
                    val last = series.points.lastOrNull()?.time
                    series.events.filter { it.kind == CurrentEventKind.SLACK && it.time in block.coverage }
                        .filter { first == null || last == null || it.time < first || it.time > last }
                        .forEach { found += occurrence(it.time, noWindow = true) }
                    if (first == null || last == null) continue
                    for (run in slackRuns(series.points, series.events, thresholdKnots)) {
                        if (run.start !in block.coverage) continue
                        if (run.start == run.end) found += occurrence(run.start, noWindow = true)
                        // The first in-band sample is not an observed opening. An observed opening can have an unknown close.
                        else if (run.start > first) found += occurrence(run.start, run.end.takeIf { it < last && it in block.coverage })
                    }
                }
                AlertTrigger.Slack -> if (series is PredictionSeries.Derived) {
                    series.slacks.filter { it.time in block.coverage }.forEach { found += occurrence(it.time) }
                }
                is AlertTrigger.CurrentPeak -> if (series is PredictionSeries.Current) {
                    val kind = if (trigger.flood) CurrentEventKind.MAX_FLOOD else CurrentEventKind.MAX_EBB
                    series.events.filter { it.kind == kind && it.time in block.coverage }.forEach { found += occurrence(it.time) }
                }
                is AlertTrigger.TideExtreme -> if (series is PredictionSeries.Tide) {
                    val kind = if (trigger.high) ExtremeKind.HIGH else ExtremeKind.LOW
                    val turns = series.events.filter { it.kind == kind && it.time in block.coverage }
                    val selected = if (trigger.ofDay) {
                        turns.groupBy { it.time.atZone(zone).toLocalDate() }.mapNotNull { (day, events) ->
                            val start = day.atStartOfDay(zone).toInstant()
                            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
                            if (start < block.coverage.start || end > block.coverage.end) null
                            else if (trigger.high) events.maxBy { it.heightMetres } else events.minBy { it.heightMetres }
                        }
                    } else turns
                    selected.forEach { found += occurrence(it.time, height = it.heightMetres) }
                }
                is AlertTrigger.TideCrossing -> if (series is PredictionSeries.Tide) {
                    val level = trigger.heightM
                    series.points.zipWithNext().forEach { (a, b) ->
                        val crosses = if (trigger.rising) a.heightMetres < level && b.heightMetres >= level
                            else a.heightMetres > level && b.heightMetres <= level
                        if (crosses) {
                            val fraction = (level - a.heightMetres) / (b.heightMetres - a.heightMetres)
                            val time = a.time.plusNanos((Duration.between(a.time, b.time).toNanos() * fraction).roundToLong())
                            if (time in block.coverage) found += occurrence(time, height = level)
                        }
                    }
                }
                AlertTrigger.Eclipse -> SunWindows.eclipseContacts(block.coverage, latitude, longitude)
                    .forEach { found += occurrence(it) }
            }
        }
        return found
    }
}
