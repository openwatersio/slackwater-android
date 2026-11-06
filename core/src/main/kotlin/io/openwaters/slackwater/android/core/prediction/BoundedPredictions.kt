package io.openwaters.slackwater.android.core.prediction

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

/** Cached official samples can answer a query only inside an uninterrupted block. */
object BoundedPredictions {
    private fun <T> slice(points: List<T>, span: TimeSpan, time: (T) -> Instant): List<T> {
        // Keep the bracketing samples so a reading at either edge can interpolate.
        val first = points.indexOfLast { time(it) <= span.start }
        val last = points.indexOfFirst { time(it) >= span.end }
        return points.subList(first, last + 1)
    }

    private fun intersect(first: Instant, last: Instant, span: TimeSpan): TimeSpan? {
        val start = maxOf(first, span.start)
        val end = minOf(last, span.end)
        return if (start <= end) TimeSpan(start, end) else null
    }

    fun tide(id: StationId, span: TimeSpan, points: List<TidePoint>): PredictionResult {
        val invalid = points.size < 2 || points.any { !it.heightMetres.isFinite() } ||
            points.zipWithNext().any { (a,b) -> b.time <= a.time || Duration.between(a.time,b.time) > Duration.ofMinutes(30) }
        if (invalid) return PredictionResult.Unavailable(PredictionResult.Reason.INVALID_MODEL)
        val coverage = intersect(points.first().time, points.last().time, span)
            ?: return PredictionResult.Unavailable(PredictionResult.Reason.OUTSIDE_COVERAGE)
        val turns = mutableListOf<TideExtreme>()
        var i = 1
        while (i < points.lastIndex) {
            var end = i
            while (end < points.lastIndex && points[end + 1].heightMetres == points[i].heightMetres) end++
            if (end == points.lastIndex) break
            val rise = points[i].heightMetres - points[i - 1].heightMetres
            val fall = points[end + 1].heightMetres - points[i].heightMetres
            if (rise * fall < 0) {
                val t = points[i].time.plus(Duration.between(points[i].time, points[end].time).dividedBy(2))
                if (t in coverage) turns += TideExtreme(t, points[i].heightMetres, if (rise > 0) ExtremeKind.HIGH else ExtremeKind.LOW)
            }
            i = end + 1
        }
        return PredictionResult.Ready(id, coverage, "CHS", StationCapabilities.TIDE_PREVIEW,
            PredictionSeries.Tide(slice(points, coverage) { it.time }, slice(points.sampledRates(), coverage) { it.time }, turns),
            previewSampleSpan = TimeSpan(points.first().time, points.last().time))
    }

    fun current(id: StationId, span: TimeSpan, points: List<CurrentPoint>, flood: Double, ebb: Double): PredictionResult {
        if (points.isEmpty() || !flood.isFinite() || !ebb.isFinite() || points.any { !it.speedKnots.isFinite() } ||
            points.zipWithNext().any { (a,b) -> a.time >= b.time }) return PredictionResult.Unavailable(PredictionResult.Reason.INVALID_MODEL)
        val covered = mutableListOf<PredictionResult.Ready>()
        var start = 0
        for (i in 1..points.size) {
            if (i < points.size && Duration.between(points[i-1].time,points[i].time) <= Duration.ofMinutes(15)) continue
            val block = points.subList(start, i)
            val coverage = intersect(block.first().time, block.last().time, span)
            if (coverage != null) {
                val events = sampleEvents(block).filter { it.time in coverage }
                covered += PredictionResult.Ready(id, coverage, "CHS", StationCapabilities.ONLINE_CURRENT,
                    PredictionSeries.Current(slice(block,coverage) { it.time }, events, flood, ebb))
            }
            start = i
        }
        return when (covered.size) {
            0 -> PredictionResult.Unavailable(PredictionResult.Reason.OUTSIDE_COVERAGE)
            1 -> covered.single()
            else -> PredictionResult.Disjoint(covered)
        }
    }

    private fun sampleEvents(points: List<CurrentPoint>): List<CurrentEvent> {
        val events = mutableListOf<CurrentEvent>()
        var peak: CurrentPoint? = null
        fun finishRun() {
            peak?.let { events += CurrentEvent(it.time, it.speedKnots,
                if (it.speedKnots > 0) CurrentEventKind.MAX_FLOOD else CurrentEventKind.MAX_EBB) }
            peak = null
        }
        if (points.size < 2) return emptyList()
        for ((i, p) in points.withIndex()) {
            val prev = points.getOrNull(i-1)
            if (p.speedKnots == 0.0) {
                if (prev != null && prev.speedKnots != 0.0) {
                    finishRun()
                    events += CurrentEvent(p.time, 0.0, CurrentEventKind.SLACK)
                }
            } else {
                if (prev != null && prev.speedKnots * p.speedKnots < 0) {
                    finishRun()
                    val fraction = prev.speedKnots / (prev.speedKnots - p.speedKnots)
                    val t = prev.time.plusNanos((Duration.between(prev.time,p.time).toNanos() * fraction).roundToLong())
                    events += CurrentEvent(t, 0.0, CurrentEventKind.SLACK)
                }
                if (peak == null || abs(p.speedKnots) > abs(requireNotNull(peak).speedKnots)) peak = p
            }
        }
        finishRun()
        return events.sortedBy { it.time }
    }
}
