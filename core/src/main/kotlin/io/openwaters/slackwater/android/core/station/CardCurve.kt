package io.openwaters.slackwater.android.core.station

import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.ExtremeKind
import io.openwaters.slackwater.android.core.prediction.PredictionResult
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.prediction.StationCapabilities
import io.openwaters.slackwater.android.core.prediction.windows
import io.openwaters.slackwater.speedAt
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Card curve geometry from list §6.4, in dp. Both the card and any surface drawing this scene read these. */
object CardCurveStyle {
    /** Headroom above and below the visible range, as a fraction of it. */
    const val DOMAIN_PAD_FRACTION = 0.25
    /** An axis time keeps this far inside either canvas edge. */
    const val AXIS_EDGE_MARGIN = 24f
    /** About one printed time's width, so neighbouring times never overlap. */
    const val AXIS_MIN_GAP = 48f
    /** A value label keeps this far inside either edge; closer, the extreme keeps only its dot and time. */
    const val LABEL_EDGE_MARGIN = 34f
    /** How far a hanging reading reaches from its dot: the value's centre plus half its line. */
    const val HANG_OFFSET = 23f
    const val HANG_VALUE_GAP = 7f
    const val HANG_GLYPH_GAP = 8f
    const val HANG_REACH = HANG_OFFSET + HANG_VALUE_GAP + 8f
    const val DOT_RADIUS = 2.5f
    /** A run narrower than a dot reads as a blot: such a window is not drawn. */
    const val SHORTEST_SLACK_STROKE = DOT_RADIUS * 2 + 1
    const val PAST_FADE = 0.45f
}

data class CurveSample(val time: Instant, val value: Double)

/**
 * A tide turn, a current peak or a derived gate's mid-swing. [high] picks the side its reading hangs toward;
 * [setDegrees] is a current peak's published bearing, [flood] a derived gate's direction with no bearing.
 */
data class CurveExtreme(
    val time: Instant,
    val value: Double,
    val high: Boolean,
    val kind: Kind,
    val setDegrees: Double? = null,
    val flood: Boolean? = null,
) {
    enum class Kind { TIDE, CURRENT, DERIVED }
}

data class CurveRun(val start: Instant, val end: Instant) {
    operator fun contains(time: Instant) = time >= start && time <= end
    val centre: Instant get() = start.plus(Duration.between(start, end).dividedBy(2))
    fun widthAt(dpPerHour: Float) = Duration.between(start, end).toMillis() / 3_600_000f * dpPerHour
    fun drawsAsDot(dpPerHour: Float) = widthAt(dpPerHour) < CardCurveStyle.DOT_RADIUS * 2
}

/**
 * The station card curve over the requested span. The list and wide widget show four swings, and the small widget shows two.
 */
class CardCurve(
    val start: Instant,
    val end: Instant,
    val now: Instant,
    val samples: List<CurveSample>,
    val extremes: List<CurveExtreme>,
    /** A current's range always includes zero, so flood and ebb read above and below the resting line. */
    val includesZero: Boolean,
    /** A derived gate's shape is a normalized phase, never a measured speed. */
    val schematic: Boolean = false,
    /** Measured slack windows at the comfort current, merged where they touch. */
    val windows: List<CurveRun> = emptyList(),
    /** Every slack instant in the span. */
    val slacks: List<Instant> = emptyList(),
) {
    /** The plotted range: the visible values padded by a quarter of their spread, and zero for a current. */
    val domain: ClosedFloatingPointRange<Double> = run {
        val visible = samples.filter { it.time >= start && it.time <= end }.map { it.value }
        var lo = visible.minOrNull() ?: 0.0
        var hi = visible.maxOrNull() ?: 1.0
        if (includesZero) {
            lo = min(lo, 0.0)
            hi = max(hi, 0.0)
        }
        val pad = max((hi - lo) * CardCurveStyle.DOMAIN_PAD_FRACTION, 0.001)
        (lo - pad)..(hi + pad)
    }

    /** Linear interpolation over the drawn samples, so run ends and the now dot ride the curve as rendered. */
    fun valueAt(time: Instant): Double {
        if (samples.isEmpty()) return 0.0
        var previous = samples.first()
        for (sample in samples) {
            if (sample.time >= time) {
                val span = Duration.between(previous.time, sample.time).toMillis()
                if (span <= 0) return sample.value
                val f = Duration.between(previous.time, time).toMillis().toDouble() / span
                return previous.value + (sample.value - previous.value) * f
            }
            previous = sample
        }
        return samples.last().value
    }

    /** Windows wide enough to draw at this scale; narrower ones would read as a dot. */
    fun drawnWindows(dpPerHour: Float) = windows.filter { !it.drawsAsDot(dpPerHour) }

    /**
     * The times the axis prints, before thinning: a tide's turns; a current's window openings (or each slack in a
     * window holding several) and every slack no drawn window holds; a derived gate's slacks. A window whose opening
     * falls before [printableFrom], too near the left edge to print, names its slacks instead.
     */
    fun axisMoments(dpPerHour: Float, printableFrom: Instant = start): List<Instant> = when {
        schematic -> slacks
        includesZero -> {
            val drawn = drawnWindows(dpPerHour)
            val windowTimes = drawn.flatMap { run ->
                val zeros = slacks.filter { it in run }
                if (zeros.size > 1 || (run.start < printableFrom && zeros.isNotEmpty())) zeros else listOf(run.start)
            }
            (windowTimes + slacks.filter { slack -> drawn.none { slack in it } }).sorted()
        }
        else -> extremes.filter { it.kind == CurveExtreme.Kind.TIDE }.map { it.time }.sorted()
    }

    /** The ink an axis time prints in: its tide turn's, or slack's on a current or derived gate (list §6.4). */
    fun axisInk(time: Instant): AxisInk? = when {
        includesZero -> AxisInk.SLACK
        else -> extremes.firstOrNull { it.kind == CurveExtreme.Kind.TIDE && it.time == time }
            ?.let { if (it.high) AxisInk.HIGH else AxisInk.LOW }
    }

    enum class AxisInk { HIGH, LOW, SLACK }

    companion object {
        val SAMPLE_STEP: Duration = Duration.ofMinutes(10)

        /**
         * The axis times a card prints, in time order: clear of both edges, each at least a time's width after the
         * last one kept. [minGap] widens the gap for a time printed wider than the nominal one, as at large text.
         */
        fun thinAxisMoments(
            moments: List<Instant>,
            x: (Instant) -> Float,
            width: Float,
            minGap: Float = CardCurveStyle.AXIS_MIN_GAP,
        ): List<Instant> {
            val gap = max(minGap, CardCurveStyle.AXIS_MIN_GAP)
            val edge = max(CardCurveStyle.AXIS_EDGE_MARGIN, gap / 2)
            val kept = mutableListOf<Instant>()
            for (time in moments.sorted()) {
                val at = x(time)
                if (at < edge || at > width - edge) continue
                if (kept.isNotEmpty() && at - x(kept.last()) < gap) continue
                kept += time
            }
            return kept
        }

        /** +1 hangs below the dot, -1 above: the preferred side, unless that would leave the plot. */
        fun hangToward(dotY: Float, high: Boolean, plotHeight: Float): Int =
            if (high) {
                if (dotY + CardCurveStyle.HANG_REACH > plotHeight) -1 else 1
            } else {
                if (dotY - CardCurveStyle.HANG_REACH < 0) 1 else -1
            }

        /**
         * A current peak's reading prefers below a crest and above a trough, judged against the velocity ten
         * minutes before; a flat or missing approach falls back to the flow's sign.
         */
        fun peakIsCrest(speed: Double, before: Double?): Boolean = when {
            before == null -> speed >= 0
            speed > before -> true
            speed < before -> false
            else -> speed >= 0
        }

        /**
         * The scene for [result] at [now], or null when no block covers [now] or its values are not finite (a
         * damaged downloaded model). A finite block, such as an online window or a tide preview, can end inside the
         * span; the curve then stops where its data does.
         */
        fun from(result: PredictionResult, now: Instant, thresholdKnots: Double, span: TimeSpan = cardSpan(now)): CardCurve? =
            build(result, now, thresholdKnots, span)?.takeIf { curve ->
                curve.samples.size >= 2 &&
                    curve.samples.all { it.value.isFinite() } && curve.extremes.all { it.value.isFinite() }
            }

        private fun build(result: PredictionResult, now: Instant, thresholdKnots: Double, span: TimeSpan): CardCurve? {
            val ready = result.blockAt(now) ?: return null
            if (now !in ready.coverage) return null
            val start = span.start
            val end = span.end
            fun inSpan(time: Instant) = time >= start && time <= end
            return when (val series = ready.series) {
                is PredictionSeries.Tide -> CardCurve(
                    start, end, now,
                    samples = series.points.filter { inSpan(it.time) }.map { CurveSample(it.time, it.heightMetres) },
                    extremes = series.events.filter { inSpan(it.time) }.map {
                        CurveExtreme(it.time, it.heightMetres, it.kind == ExtremeKind.HIGH, CurveExtreme.Kind.TIDE)
                    },
                    includesZero = false,
                )
                is PredictionSeries.Current -> {
                    val points = series.points
                    val slacks = series.events.filter { it.kind == CurrentEventKind.SLACK && inSpan(it.time) }.map { it.time }
                    CardCurve(
                        start, end, now,
                        samples = points.filter { inSpan(it.time) }.map { CurveSample(it.time, it.speedKnots) },
                        // A finite block (an online window) marks its largest sample before a cut as a peak; a peak
                        // on the data's first or last sample is that cut, not the water's turn, so it is dropped.
                        extremes = series.events.filter {
                            it.kind != CurrentEventKind.SLACK && inSpan(it.time) &&
                                it.time != points.firstOrNull()?.time && it.time != points.lastOrNull()?.time
                        }.map { event ->
                            val before = points.speedAt(event.time.minus(SAMPLE_STEP))
                            CurveExtreme(
                                event.time, event.speedKnots, peakIsCrest(event.speedKnots, before), CurveExtreme.Kind.CURRENT,
                                // The curve keeps the major axis: a peak sets along the published bearing.
                                setDegrees = if (event.speedKnots >= 0) series.floodBearing else series.ebbBearing,
                            )
                        },
                        includesZero = true,
                        windows = if (thresholdKnots <= 0 || points.size < 2) emptyList()
                        // The data's own spacing, so an online block's 15-minute samples are judged on their grid.
                        else series.windows(thresholdKnots, Duration.between(points[0].time, points[1].time).takeIf { !it.isNegative && !it.isZero } ?: SAMPLE_STEP)
                            .filter { it.end > it.start }
                            // Clipped to the span, so a run never draws past the card's edges.
                            .mapNotNull { run ->
                                val from = maxOf(run.start, start)
                                val to = minOf(run.end, end)
                                if (to > from) CurveRun(from, to) else null
                            },
                        slacks = slacks,
                    )
                }
                is PredictionSeries.Derived -> derived(series, start, end, now)
            }
        }

        // A derived gate has slack times only: a schematic ±1 swing between known slacks, positive toward a
        // high-water slack (flood) and negative toward a low-water one, with no speed, set or window.
        private fun derived(series: PredictionSeries.Derived, start: Instant, end: Instant, now: Instant): CardCurve? {
            val slacks = series.slacks.sortedBy { it.time }
            // The shape is drawn only between slacks the data gives, never invented past them.
            if (slacks.size < 2 || slacks.none { it.time >= start && it.time <= end }) return null
            fun signTo(next: PredictionSeries.DerivedSlack) = if (next.referenceTurn == ExtremeKind.HIGH) 1.0 else -1.0
            fun value(time: Instant): Double {
                val nextIndex = slacks.indexOfFirst { it.time > time }
                val (a, b, sign) = when (nextIndex) {
                    -1 -> Triple(slacks[slacks.size - 2].time, slacks.last().time, -signTo(slacks.last()))
                        .let { (p, q, s) -> Triple(q, q.plus(Duration.between(p, q)), s) }
                    0 -> Triple(slacks[0].time.minus(Duration.between(slacks[0].time, slacks[1].time)), slacks[0].time, signTo(slacks[0]))
                    else -> Triple(slacks[nextIndex - 1].time, slacks[nextIndex].time, signTo(slacks[nextIndex]))
                }
                val span = Duration.between(a, b).toMillis().toDouble()
                if (span <= 0) return 0.0
                val f = (Duration.between(a, time).toMillis() / span).coerceIn(0.0, 1.0)
                return sign * sin(PI * f)
            }
            // Only between the first and last slack the data gives: beyond them a swing would be invented, and a
            // flat line on zero would read as slack water.
            val first = maxOf(start, slacks.first().time)
            val last = minOf(end, slacks.last().time)
            val samples = generateSequence(first) { it.plus(SAMPLE_STEP) }.takeWhile { it < last }
                .map { CurveSample(it, value(it)) }.toList() + CurveSample(last, value(last))
            val extremes = slacks.zipWithNext().mapNotNull { (a, b) ->
                val time = a.time.plus(Duration.between(a.time, b.time).dividedBy(2))
                if (time < start || time > end) return@mapNotNull null
                val flood = b.referenceTurn == ExtremeKind.HIGH
                CurveExtreme(time, value(time), flood, CurveExtreme.Kind.DERIVED, flood = flood)
            }
            return CardCurve(
                start, end, now, samples, extremes, includesZero = true, schematic = true,
                slacks = slacks.map { it.time }.filter { it >= start && it <= end },
            )
        }
    }
}

/** The block a card at [at] reads from: the result itself, or the disjoint block covering that moment. */
fun PredictionResult.blockAt(at: Instant): PredictionResult.Ready? = when (this) {
    is PredictionResult.Ready -> this
    is PredictionResult.Disjoint -> blocks.firstOrNull { at in it.coverage }
    is PredictionResult.Unavailable -> null
}

/** Whether a reading from this result is approximate and its curve values print with "~". */
val PredictionResult.Ready.approximate: Boolean
    get() = capabilities == StationCapabilities.PROVISIONAL_CURRENT || capabilities == StationCapabilities.BOUNDED_CURRENT
