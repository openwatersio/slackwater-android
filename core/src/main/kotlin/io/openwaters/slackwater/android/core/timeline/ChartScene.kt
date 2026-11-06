package io.openwaters.slackwater.android.core.timeline

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.prediction.ChartModel
import io.openwaters.slackwater.android.core.prediction.readingAt
import io.openwaters.slackwater.android.core.station.*
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.*

enum class ChartStop(val glyph: String = "") {
    High("↑"), Low("↓"), Rise("↗"), Fall("↘"), Slack("→│←"), Flood, Ebb, Open("→│←"),
    Close("←│→"), Sunrise("☼↑"), Sunset("☼↓")
}

data class ChartMark(val at: Instant, val stop: ChartStop)
data class ChartPoint(val at: Instant, val value: Double, val strength: Double = 0.0)
data class ChartLead(
    val state: String,
    val value: String = "",
    val unit: String = "",
    val set: String = "",
    val bearing: Double? = null,
    val rate: Double? = null
)

class ChartScene(
    val series: PredictionSeries,
    days: List<TimelineDay>,
    threshold: Double,
    showSky: Boolean
) {
    val derived = series is PredictionSeries.Derived
    val tide = series is PredictionSeries.Tide
    val points = when (series) {
        is PredictionSeries.Tide -> series.points.mapIndexed { i, p ->
            ChartPoint(
                p.time,
                p.heightMetres,
                abs(series.rates.getOrNull(i)?.rateMetresPerHour ?: 0.0)
            )
        }

        is PredictionSeries.Current -> series.points.map {
            ChartPoint(
                it.time,
                it.speedKnots,
                it.magnitudeKnots
            )
        }

        is PredictionSeries.Derived -> buildList {
            for ((a, b) in series.slacks.zipWithNext()) {
                val length = Duration.between(a.time, b.time).toMillis()
                val steps = (length / 600000).coerceIn(2, 144).toInt()
                val sign = if (a.referenceTurn == ExtremeKind.LOW) 1 else -1
                for (i in 0 until steps) add(
                    ChartPoint(
                        a.time.plusMillis(length * i / steps),
                        sign * sin(PI * i / steps)
                    )
                )
            }
            series.slacks.lastOrNull()?.let { add(ChartPoint(it.time, 0.0)) }
        }
    }
    val windows = if (series is PredictionSeries.Current && threshold > 0)
        slackRuns(series.points, series.events, threshold).filter {
            Duration.between(
                it.start,
                it.end
            ).seconds >= 1000
        }
    else emptyList()
    val water = when (series) {
        is PredictionSeries.Tide -> series.events.map {
            ChartMark(
                it.time,
                if (it.kind == ExtremeKind.HIGH) ChartStop.High else ChartStop.Low
            )
        } +
                flowPeaks(series.rates, 0.6).map {
                    ChartMark(
                        it.time,
                        if (it.rateMetresPerHour > 0) ChartStop.Rise else ChartStop.Fall
                    )
                }

        is PredictionSeries.Current -> series.events.map {
            ChartMark(
                it.time, when (it.kind) {
                    CurrentEventKind.SLACK -> ChartStop.Slack
                    CurrentEventKind.MAX_FLOOD -> ChartStop.Flood
                    CurrentEventKind.MAX_EBB -> ChartStop.Ebb
                }
            )
        } + windows.flatMap { run ->
            listOfNotNull(
                ChartMark(
                    run.start,
                    ChartStop.Open
                ).takeUnless { run.start == points.firstOrNull()?.at },
                ChartMark(
                    run.end,
                    ChartStop.Close
                ).takeUnless { run.end == points.lastOrNull()?.at })
        }

        is PredictionSeries.Derived -> series.slacks.map { ChartMark(it.time, ChartStop.Slack) }
    }.sortedBy { it.at }

    // Leave a halo only where a slack/window stroke meets yellow. A zero inside a window has no seam.
    val slackInkEdges = if (series is PredictionSeries.Current) buildList<Pair<Instant, Instant>> {
        val strokes =
            windows.map { it.start to it.end } + water.filter { it.stop == ChartStop.Slack }
                .map { it.at.minusSeconds(600) to it.at.plusSeconds(600) }
        for ((start, end) in strokes.sortedBy { it.first }) {
            val previous = lastOrNull()
            if (previous != null && previous.second >= start) {
                this[lastIndex] = previous.first to maxOf(previous.second, end)
            } else add(start to end)
        }
    } else emptyList()
    val sun = days.flatMap { day ->
        listOfNotNull(
            day.sunrise?.let { ChartMark(it, ChartStop.Sunrise) },
            day.sunset?.let { ChartMark(it, ChartStop.Sunset) })
    }.sortedBy { it.at }
    private val stops = ((if (showSky) sun else emptyList()) + water).sortedBy { it.at }.filter {
        points.isNotEmpty() && it.at >= points.first().at && it.at <= points.last().at
    }

    fun commentary(time: Instant): ChartMark? {
        var lower = 0
        var upper = stops.size
        val after = time.plusSeconds(1)
        while (lower < upper) {
            val mid = (lower + upper) ushr 1
            if (stops[mid].at <= after) lower = mid + 1 else upper = mid
        }
        return stops.getOrNull(lower)
    }

    fun lead(
        time: Instant,
        feet: Boolean,
        speed: SpeedUnitKind,
        degrees: Boolean,
        locale: Locale,
        model: ChartModel? = null
    ): ChartLead {
        return when (series) {
            is PredictionSeries.Tide -> readTide(
                time,
                series.points,
                series.rates,
                series.events
            )?.let {
                val unit = if (feet) "ft" else "m"
                var state = it.state.name.lowercase().replaceFirstChar(Char::uppercase)
                if (abs(it.rateMetresPerHour) >= 0.6)
                    state += " " + formatHeightValue(
                        abs(it.rateMetresPerHour),
                        feet,
                        locale
                    ) + " " + unit + "/hr"
                ChartLead(
                    state,
                    formatHeightValue(
                        (model as? ChartModel.Tide)?.height(time) ?: it.heightMetres,
                        feet,
                        locale
                    ),
                    unit,
                    rate = abs(it.rateMetresPerHour).takeIf { rate -> rate >= 0.6 })
            }

            is PredictionSeries.Current -> ((model as? ChartModel.Current)?.reading(time, series)
                ?: series.readingAt(time))?.let {
                val unit = when (speed) {
                    SpeedUnitKind.KNOTS -> "kn"; SpeedUnitKind.KILOMETRES_PER_HOUR -> "km/h"; SpeedUnitKind.METRES_PER_SECOND -> "m/s"
                }
                val set = it.directionDegrees?.takeIf { _ ->
                    it.state != CurrentState.SLACK ||
                            series.hasCrossAxis && it.magnitudeKnots * speed.perKnot >= 0.05
                }
                ChartLead(
                    it.state.name.lowercase().replace('_', ' ')
                    .replaceFirstChar(Char::uppercase),
                    formatSpeedValue(it.magnitudeKnots, speed, locale),
                    unit,
                    set?.let { angle -> formatSet(angle, degrees) }.orEmpty(),
                    set
                )
            }

            is PredictionSeries.Derived -> ChartLead(
                (derivedPhase(series.slacks, time) ?: CardCurrentState.SLACK)
                    .name.lowercase().replaceFirstChar(Char::uppercase)
            )
        } ?: ChartLead("Unavailable")
    }

    fun at(time: Instant): Double? {
        val index = points.binarySearchBy(time) { it.at }
        if (index >= 0) return points[index].value
        val i = -index - 1
        if (i !in 1 until points.size) return if (derived && points.isNotEmpty()) 0.0 else null
        val a = points[i - 1]
        val b = points[i]
        return a.value + (b.value - a.value) * Duration.between(a.at, time)
            .toMillis() / Duration.between(a.at, b.at).toMillis()
    }
}
