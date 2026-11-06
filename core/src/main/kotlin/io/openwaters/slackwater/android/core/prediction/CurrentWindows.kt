package io.openwaters.slackwater.android.core.prediction

import io.openwaters.slackwater.*
import java.time.Duration
import java.time.Instant

data class CurrentWindow(val start: Instant, val end: Instant, val clipped: Boolean, val slacks: List<Instant>) {
    val duration: Duration? get() = if (clipped || start == end) null else Duration.between(start, end)
}

/** Only engine slack events can anchor a measured window. A small same-sign speed alone is weak water. */
fun PredictionSeries.Current.windows(thresholdKnots: Double, sampleStep: Duration): List<CurrentWindow> {
    require(thresholdKnots.isFinite() && thresholdKnots >= 0)
    require(!sampleStep.isZero && !sampleStep.isNegative)
    val slacks = events.filter { it.kind == CurrentEventKind.SLACK }.map { it.time }.distinct().sorted()
    return slackRuns(points, events, thresholdKnots).flatMap { run ->
        val inside = slacks.filter { it >= run.start && it <= run.end }
        val clipped = run.start == points.first().time || run.end == points.last().time
        if (thresholdKnots == 0.0 || (!clipped && Duration.between(run.start, run.end) < sampleStep)) {
            inside.map { CurrentWindow(it, it, false, listOf(it)) }
        } else listOf(CurrentWindow(run.start, run.end, clipped, inside))
    }
}
