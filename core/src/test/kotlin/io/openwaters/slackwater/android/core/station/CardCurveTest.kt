package io.openwaters.slackwater.android.core.station

import io.openwaters.slackwater.CurrentEvent
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.ExtremeKind
import io.openwaters.slackwater.TideExtreme
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.TideRatePoint
import io.openwaters.slackwater.android.core.prediction.PredictionResult
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.prediction.StationCapabilities
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import org.junit.Assert.*
import org.junit.Test

class CardCurveTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private val span = cardSpan(now)
    private fun minutes(m: Long) = now.plus(Duration.ofMinutes(m))
    private val grid = generateSequence(span.start.minus(Duration.ofHours(1))) { it.plus(Duration.ofMinutes(10)) }
        .takeWhile { it <= span.end.plus(Duration.ofHours(1)) }.toList()

    private fun ready(series: PredictionSeries, capabilities: StationCapabilities) =
        PredictionResult.Ready(StationId("test/1"), TimeSpan(grid.first(), grid.last()), "test", capabilities, series)

    // A 12.42-hour tide between 1 m and 3 m; highs and lows read straight off the sampled cosine.
    private val tide: PredictionSeries.Tide = run {
        val period = 12.42 * 60
        fun height(t: Instant) = 2.0 + cos(2 * PI * Duration.between(now, t).toMinutes() / period)
        val events = (-3..6).map { half ->
            val t = now.plus(Duration.ofSeconds((half * period / 2 * 60).toLong()))
            TideExtreme(t, height(t), if (half % 2 == 0) ExtremeKind.HIGH else ExtremeKind.LOW)
        }
        PredictionSeries.Tide(grid.map { TidePoint(it, height(it)) }, grid.map { TideRatePoint(it, 0.0) }, events)
    }

    @Test fun aTideSpansTheCardWindowAndPadsItsRange() {
        val curve = CardCurve.from(ready(tide, StationCapabilities.HARMONIC_TIDE), now, 0.5)!!
        assertEquals(span.start, curve.start)
        assertEquals(span.end, curve.end)
        assertTrue(curve.samples.all { it.time >= span.start && it.time <= span.end })
        assertFalse(curve.includesZero)
        // Values run 1–3 m, so a quarter of the 2 m spread pads each side.
        assertEquals(0.5, curve.domain.start, 1e-3)
        assertEquals(3.5, curve.domain.endInclusive, 1e-3)
        assertTrue(curve.extremes.all { it.kind == CurveExtreme.Kind.TIDE && it.time >= span.start && it.time <= span.end })
        assertEquals(curve.extremes.map { it.time }, curve.axisMoments(10f))
        assertEquals(3.0, curve.valueAt(now), 1e-3)
    }

    // A flood peaking at +2 kn at 1 h, slack at 4 h, then ebb, with a second slack the window cannot merge.
    private fun current(): PredictionSeries.Current {
        fun speed(t: Instant) = 2.0 * cos(2 * PI * (Duration.between(now, t).toMinutes() - 60) / (12.42 * 60))
        // The zero crossing a quarter period after the flood peak.
        val slackA = now.plus(Duration.ofSeconds(((60 + 12.42 * 60 / 4) * 60).toLong()))
        val events = buildList {
            add(CurrentEvent(minutes(60), 2.0, CurrentEventKind.MAX_FLOOD, 90.0))
            add(CurrentEvent(slackA, 0.0, CurrentEventKind.SLACK))
            add(CurrentEvent(minutes(60 + 373), -2.0, CurrentEventKind.MAX_EBB, 270.0))
        }
        return PredictionSeries.Current(grid.map { CurrentPoint(it, speed(it)) }, events, 90.0, 270.0)
    }

    @Test fun aCurrentKeepsZeroInRangeAndDrawsItsWindows() {
        val series = current()
        val curve = CardCurve.from(ready(series, StationCapabilities.HARMONIC_CURRENT), now, 0.5)!!
        assertTrue(curve.includesZero)
        assertTrue(curve.domain.start < -2.0 && curve.domain.endInclusive > 2.0)
        assertEquals(listOf(90.0, 270.0), curve.extremes.map { it.setDegrees })
        assertEquals(listOf(true, false), curve.extremes.map { it.high })
        assertEquals(1, curve.windows.size)
        assertTrue(series.events[1].time in curve.windows.single())
        // One green time at the window's opening.
        assertEquals(listOf(curve.windows.single().start), curve.axisMoments(10f))
        // Off, the comfort current draws no window; slack keeps its own time.
        val off = CardCurve.from(ready(series, StationCapabilities.HARMONIC_CURRENT), now, 0.0)!!
        assertTrue(off.windows.isEmpty())
        assertEquals(listOf(series.events[1].time), off.axisMoments(10f))
    }

    @Test fun aWindowHoldingSeveralSlacksNamesEachAndABareSlackKeepsItsTime() {
        val a = minutes(100)
        val b = minutes(140)
        val c = minutes(600)
        val curve = CardCurve(
            span.start, span.end, now, emptyList(), emptyList(), includesZero = true,
            windows = listOf(CurveRun(minutes(80), minutes(160))), slacks = listOf(a, b, c),
        )
        assertEquals(listOf(a, b, c), curve.axisMoments(10f))
        // At a scale where the window is narrower than a dot it is not drawn, so each slack prints alone.
        assertEquals(listOf(a, b, c), curve.axisMoments(0.01f))
        assertTrue(curve.drawnWindows(0.01f).isEmpty())
    }

    @Test fun axisTimesKeepClearOfTheEdgesAndEachOther() {
        val times = listOf(0f, 23f, 30f, 60f, 79f, 120f, 280f, 300f).map { now.plusSeconds(it.toLong()) }
        val x = { t: Instant -> Duration.between(now, t).seconds.toFloat() }
        // 0 and 23 sit inside the 24 dp edge, 60 is under 48 dp after 30 and 120 under 48 dp after 79, and 300 is
        // past width − 24.
        assertEquals(listOf(30f, 79f, 280f).map { now.plusSeconds(it.toLong()) }, CardCurve.thinAxisMoments(times, x, 320f))
    }

    @Test fun readingsHangOnTheirPreferredSideUnlessThatLeavesThePlot() {
        assertEquals(1, CardCurve.hangToward(dotY = 10f, high = true, plotHeight = 100f))
        assertEquals(-1, CardCurve.hangToward(dotY = 70f, high = true, plotHeight = 100f))
        assertEquals(-1, CardCurve.hangToward(dotY = 90f, high = false, plotHeight = 100f))
        assertEquals(1, CardCurve.hangToward(dotY = 20f, high = false, plotHeight = 100f))
    }

    @Test fun aPeakIsACrestByItsApproachNotOnlyItsSign() {
        assertTrue(CardCurve.peakIsCrest(1.0, 0.8))
        // A relaxation peak in a flood that never reverses: falling into it, it is a trough.
        assertFalse(CardCurve.peakIsCrest(0.4, 0.6))
        assertTrue(CardCurve.peakIsCrest(0.5, 0.5))
        assertFalse(CardCurve.peakIsCrest(-0.5, null))
    }

    @Test fun aDerivedGateDrawsASchematicSwingBetweenItsSlacks() {
        val slacks = listOf(
            PredictionSeries.DerivedSlack(minutes(-300), ExtremeKind.LOW),
            PredictionSeries.DerivedSlack(minutes(60), ExtremeKind.HIGH),
            PredictionSeries.DerivedSlack(minutes(420), ExtremeKind.LOW),
            PredictionSeries.DerivedSlack(minutes(780), ExtremeKind.HIGH),
        )
        val curve = CardCurve.from(ready(PredictionSeries.Derived(slacks), StationCapabilities.DERIVED_TIMING), now, 0.5)!!
        assertTrue(curve.schematic)
        assertTrue(curve.windows.isEmpty())
        // Toward the high-water slack the water floods (+); toward the low-water one it ebbs (−).
        assertTrue(curve.valueAt(minutes(-120)) > 0.9)
        assertTrue(curve.valueAt(minutes(240)) < -0.9)
        assertEquals(0.0, curve.valueAt(minutes(60)), 0.05)
        assertEquals(listOf(true, false, true), curve.extremes.map { it.flood })
        assertEquals(curve.slacks, curve.axisMoments(10f))
        assertTrue(curve.samples.all { it.value in -1.0..1.0 })
    }

    @Test fun aWindowOpeningOffTheLeftEdgeNamesItsSlack() {
        val slack = minutes(-500)
        val curve = CardCurve(
            span.start, span.end, now, emptyList(), emptyList(), includesZero = true,
            windows = listOf(CurveRun(span.start, minutes(-480))), slacks = listOf(slack),
        )
        assertEquals(listOf(span.start), curve.axisMoments(10f))
        assertEquals(listOf(slack), curve.axisMoments(10f, printableFrom = span.start.plus(Duration.ofHours(1))))
    }

    @Test fun windowsAreClippedToTheSpan() {
        val curve = CardCurve.from(ready(current(), StationCapabilities.HARMONIC_CURRENT), now, 3.0)!!
        assertTrue(curve.windows.isNotEmpty())
        assertTrue(curve.windows.all { it.start >= span.start && it.end <= span.end })
    }

    @Test fun aDamagedModelDrawsNoCurve() {
        val broken = tide.copy(events = tide.events.map { it.copy(heightMetres = Double.NaN) })
        assertNull(CardCurve.from(ready(broken, StationCapabilities.FITTED_TIDE), now, 0.5))
        val fast = current().let { it.copy(points = it.points.map { p -> p.copy(speedKnots = Double.POSITIVE_INFINITY) }) }
        assertNull(CardCurve.from(ready(fast, StationCapabilities.FINAL_CURRENT), now, 0.5))
    }

    @Test fun aDerivedGateIsNotDrawnFromSlacksOutsideTheSpan() {
        val early = PredictionSeries.Derived(listOf(
            PredictionSeries.DerivedSlack(span.start.minus(Duration.ofHours(10)), ExtremeKind.LOW),
            PredictionSeries.DerivedSlack(span.start.minus(Duration.ofHours(4)), ExtremeKind.HIGH),
        ))
        assertNull(CardCurve.from(ready(early, StationCapabilities.DERIVED_TIMING), now, 0.5))
    }

    @Test fun wideTimesWidenTheGapAndTheEdge() {
        val x = { t: Instant -> Duration.between(now, t).seconds.toFloat() }
        val times = listOf(40f, 100f, 180f).map { now.plusSeconds(it.toLong()) }
        assertEquals(listOf(40f, 100f, 180f).map { now.plusSeconds(it.toLong()) }, CardCurve.thinAxisMoments(times, x, 320f))
        assertEquals(listOf(100f).map { now.plusSeconds(it.toLong()) }, CardCurve.thinAxisMoments(times, x, 320f, minGap = 90f))
    }

    @Test fun aDerivedShapeStopsAtTheLastSlackItKnows() {
        val slacks = listOf(
            PredictionSeries.DerivedSlack(minutes(-300), ExtremeKind.LOW),
            PredictionSeries.DerivedSlack(minutes(220), ExtremeKind.HIGH),
            PredictionSeries.DerivedSlack(minutes(442), ExtremeKind.LOW),
        )
        val curve = CardCurve.from(ready(PredictionSeries.Derived(slacks), StationCapabilities.DERIVED_TIMING), now, 0.5)!!
        assertEquals(minutes(-300), curve.samples.first().time)
        assertEquals(minutes(442), curve.samples.last().time)
    }

    @Test fun aPeakOnTheEdgeOfFiniteDataIsNotATurn() {
        // An online block ending while the flood still builds: its last sample is the largest, not a peak.
        val points = (0..30).map { CurrentPoint(minutes(it * 15L - 200), 0.05 * it) }
        val block = PredictionSeries.Current(
            points, listOf(CurrentEvent(points.last().time, points.last().speedKnots, CurrentEventKind.MAX_FLOOD)), 90.0, 270.0,
        )
        val result = PredictionResult.Ready(StationId("chs-gate"), TimeSpan(points.first().time, points.last().time), "test",
            StationCapabilities.ONLINE_CURRENT, block)
        val curve = CardCurve.from(result, now, 0.5)!!
        assertTrue(curve.extremes.isEmpty())
    }

    @Test fun aDisjointResultDrawsTheBlockCoveringNowAndCarriesItsApproximation() {
        val early = ready(tide, StationCapabilities.HARMONIC_TIDE).copy(coverage = TimeSpan(minutes(-600), minutes(-60)))
        val covering = ready(current(), StationCapabilities.BOUNDED_CURRENT)
        val result = PredictionResult.Disjoint(listOf(early, covering))
        val curve = CardCurve.from(result, now, 0.5)!!
        assertTrue(curve.includesZero)
        assertEquals(covering, result.blockAt(now))
        assertTrue(result.blockAt(now)!!.approximate)
        assertFalse(ready(current(), StationCapabilities.FINAL_CURRENT).approximate)
    }

    @Test fun windowsAreJudgedOnTheDataOwnSpacing() {
        val slack = minutes(30)
        fun online(knotsPerMinute: Double): PredictionResult.Ready {
            val points = (-40..40).map { CurrentPoint(minutes(it * 15L), knotsPerMinute * (it * 15L - 30)) }
            val block = PredictionSeries.Current(points, listOf(CurrentEvent(slack, 0.0, CurrentEventKind.SLACK)), 90.0, 270.0)
            return PredictionResult.Ready(StationId("chs-gate"), TimeSpan(points.first().time, points.last().time), "test",
                StationCapabilities.ONLINE_CURRENT, block)
        }
        // Fifteen-minute online samples: a 25-minute window, longer than one sample step, is drawn around its slack.
        val wide = CardCurve.from(online(0.04), now, 0.5)!!
        assertEquals(1, wide.windows.size)
        assertTrue(slack in wide.windows.single())
        // A 12.5-minute window is shorter than the data's step though longer than the curve's own: just its slack.
        val narrow = CardCurve.from(online(0.08), now, 0.5)!!
        assertEquals(emptyList<CurveRun>(), narrow.windows)
        assertEquals(listOf(slack), narrow.slacks)
    }

    @Test fun noCurveWithoutACoveringSeries() {
        assertNull(CardCurve.from(PredictionResult.Unavailable(PredictionResult.Reason.MISSING_DOWNLOAD), now, 0.5))
        val late = ready(tide, StationCapabilities.HARMONIC_TIDE).copy(coverage = TimeSpan(minutes(60), minutes(120)))
        assertNull(CardCurve.from(late, now, 0.5))
        val oneSlack = PredictionSeries.Derived(listOf(PredictionSeries.DerivedSlack(minutes(60), ExtremeKind.HIGH)))
        assertNull(CardCurve.from(ready(oneSlack, StationCapabilities.DERIVED_TIMING), now, 0.5))
    }
}
