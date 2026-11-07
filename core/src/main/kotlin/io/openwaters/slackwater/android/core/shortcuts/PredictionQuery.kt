package io.openwaters.slackwater.android.core.shortcuts

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.settings.ReadingSettings
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

enum class QueryKind { HIGH, LOW, SLACK }

/** A missing boundary means the curve ended before the crossing could be observed. */
data class QueryWindow(val start: Instant?, val end: Instant?, val highlight: Instant)

sealed interface QueryAnswer {
    data object Unavailable : QueryAnswer
    data object Off : QueryAnswer
    data object None : QueryAnswer
    data class Tides(val events: List<TideExtreme>, val curve: PredictionSeries.Tide) : QueryAnswer
    data class Windows(val windows: List<QueryWindow>, val curve: PredictionSeries.Current) : QueryAnswer
}

private val windowMargin = Duration.ofHours(6)
private val horizon = Duration.ofHours(48)
// Wide widget cards look back 1.5 swings of the 12.42-hour semidiurnal tide.
private val cardLookback = Duration.ofSeconds(33_534)

suspend fun queryPrediction(
    repository: PredictionRepository,
    station: StationIdentity,
    kind: QueryKind,
    now: Instant,
    date: LocalDate?,
    settings: ReadingSettings,
): QueryAnswer {
    val zone = station.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
    val from = date?.atStartOfDay(zone)?.toInstant() ?: now
    val until = date?.plusDays(1)?.atStartOfDay(zone)?.toInstant() ?: now.plus(horizon)
    val lookback = from.minus(cardLookback)
    val span = if (kind == QueryKind.SLACK && station.kind == StationKind.CanadianCurrent) {
        // Online gates require one saved block covering the entire widget loader interval.
        val midnight = from.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        TimeSpan(minOf(midnight, lookback), from.plus(horizon).plus(windowMargin))
    } else {
        TimeSpan(lookback, if (kind == QueryKind.SLACK) until.plus(windowMargin) else until)
    }
    val result = repository.load(station.id, span, PredictionPurpose.QUERY) as? PredictionResult.Ready
        ?: return QueryAnswer.Unavailable
    if (!result.capabilities.supports(PredictionPurpose.QUERY) ||
        result.coverage.start > span.start || result.coverage.end < span.end) return QueryAnswer.Unavailable

    if (kind != QueryKind.SLACK) {
        val curve = result.series as? PredictionSeries.Tide ?: return QueryAnswer.Unavailable
        val extreme = if (kind == QueryKind.HIGH) ExtremeKind.HIGH else ExtremeKind.LOW
        val matches = curve.events.filter {
            it.kind == extreme && if (date == null) it.time > from && it.time <= until else it.time >= from && it.time < until
        }.sortedBy { it.time }
        if (matches.isEmpty()) return QueryAnswer.None
        return QueryAnswer.Tides(if (date == null) matches.take(1) else matches, curve)
    }

    val curve = result.series as? PredictionSeries.Current ?: return QueryAnswer.Unavailable
    val threshold = settings.effectiveThresholdKn(station.id)
    if (threshold == 0.0) return QueryAnswer.Off
    val slackSpan = TimeSpan(from.minus(windowMargin), if (date == null) until else until.plus(windowMargin))
    val aroundSlacks = curve.events.asSequence()
        .filter { it.kind == CurrentEventKind.SLACK && it.time in slackSpan }
        .mapNotNull { slackWindowAround(curve.points, it.time, threshold) }
        .sortedBy { it.start }.toList()
    val windows = mergeWindows(aroundSlacks)
        .filter { it.end > from && it.start < until }
        .map {
            QueryWindow(it.start.takeIf { time -> time > curve.points.first().time },
                it.end.takeIf { time -> time < curve.points.last().time }, maxOf(it.start, from))
        }
    if (windows.isEmpty()) return QueryAnswer.None
    return QueryAnswer.Windows(if (date == null) windows.take(1) else windows, curve)
}
