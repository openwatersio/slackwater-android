package io.openwaters.slackwater.android.core.timeline

import io.openwaters.almanac.AlmanacOutOfRangeException
import io.openwaters.almanac.MoonEventKind
import io.openwaters.almanac.Observer
import io.openwaters.almanac.SunEventKind
import io.openwaters.almanac.moonEvents
import io.openwaters.almanac.sunEvents
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Load with context on both sides. Chunk ownership is half-open, including at DST transitions. */
suspend fun PredictionRepository.timelineChunk(
    station: StationIdentity,
    zone: ZoneId,
    window: TimelineWindow,
): TimelineChunkData {
    val pad = Duration.ofHours(if (station.kind == StationKind.DerivedGate) 24 else 6)
    val span = TimeSpan(window.start.minus(pad), window.end.plus(pad))
    val result = load(station.id, span, PredictionPurpose.DISPLAY)
    require(result is PredictionResult.Ready && result.capabilities.continuous &&
        span.start in result.coverage && span.end in result.coverage) {
        "Continuous predictions do not cover the requested chunk"
    }
    val data = when (val series = result.series) {
        is PredictionSeries.Tide -> {
            requireSampleGrid(series.points.map { it.time }, window)
            requireSampleGrid(series.rates.map { it.time }, window)
            require(series.points.all { it.heightMetres.isFinite() } && series.rates.all { it.rateMetresPerHour.isFinite() })
            TimelineChunkData(tidePoints = series.points, tideRates = series.rates, tideEvents = series.events)
        }
        is PredictionSeries.Current -> {
            requireSampleGrid(series.points.map { it.time }, window)
            require(series.points.all { it.speedKnots.isFinite() && it.magnitudeKnots.isFinite() })
            TimelineChunkData(currentPoints = series.points, currentEvents = series.events,
                floodBearing = series.floodBearing, ebbBearing = series.ebbBearing, hasCrossAxis = series.hasCrossAxis)
        }
        is PredictionSeries.Derived -> TimelineChunkData(derivedSlacks = series.slacks)
    }
    return data.copy(days = timelineDays(station, zone, window)).clipped(window)
}

suspend fun timelineDays(station: StationIdentity, zone: ZoneId, window: TimelineWindow): List<TimelineDay> {
    val observer = if (station.latitude in -90.0..90.0 && station.longitude in -180.0..180.0)
        Observer(station.latitude, station.longitude) else null
    val first = window.start.atZone(zone).toLocalDate().minusDays(1)
    val last = window.end.atZone(zone).toLocalDate()
    val context = currentCoroutineContext()
    return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.map { day ->
        context.ensureActive()
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        if (observer == null) return@map TimelineDay(day)
        val (sun, moon) = try {
            sunEvents(start, end, observer) to moonEvents(start, end, observer)
        } catch (_: AlmanacOutOfRangeException) {
            return@map TimelineDay(day)
        }
        TimelineDay(day, sun.firstOrNull { it.kind == SunEventKind.RISE }?.time,
            sun.firstOrNull { it.kind == SunEventKind.SET }?.time,
            sun.firstOrNull { it.kind == SunEventKind.TRANSIT }?.time,
            moon.firstOrNull { it.kind == MoonEventKind.RISE }?.time,
            moon.firstOrNull { it.kind == MoonEventKind.SET }?.time)
    }.toList()
}

// Western Alaska's first sunset of a civil day can precede that day's sunrise.
fun TimelineDay.dateAnchor(days: List<TimelineDay>, zone: ZoneId): Instant {
    val noon = date.atTime(12, 0).atZone(zone).toInstant()
    val rise = sunrise ?: return noon
    val nextSet = days.asSequence().mapNotNull { it.sunset }.filter { it > rise }.minOrNull() ?: return noon
    if (days.any { it.sunrise != null && it.sunrise > rise && it.sunrise < nextSet }) return noon
    return rise.plus(Duration.between(rise, nextSet).dividedBy(2))
}

private fun requireSampleGrid(times: List<Instant>, window: TimelineWindow) {
    require(times.isNotEmpty() && times.first() <= window.start && times.last() >= window.end &&
        times.zipWithNext().all { (a, b) -> a < b && b <= a.plusSeconds(600) }) {
        "Continuous timeline requires uninterrupted ten-minute samples"
    }
}
