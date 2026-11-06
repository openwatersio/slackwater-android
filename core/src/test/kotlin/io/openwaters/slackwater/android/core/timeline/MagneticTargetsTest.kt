package io.openwaters.slackwater.android.core.timeline

import io.openwaters.almanac.*
import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.BoundedPredictions
import io.openwaters.slackwater.android.core.prediction.PredictionResult
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MagneticTargetsTest {
    private val start = Instant.parse("2024-04-08T00:00:00Z")
    private fun t(seconds: Long) = start.plusSeconds(seconds)
    private val window = TimelineWindow(start, t(86400))
    private fun targets(series: PredictionSeries) =
        MagneticTargets.from(window, series, 0.5, Double.NaN, Double.NaN, false)

    @Test fun captureIsStrictAndEarlierTargetWinsEqualDistance() {
        val targets = MagneticTargets(listOf(t(2000), start, t(400), start))
        assertEquals(start, targets.capture(t(200)))
        assertEquals(t(2000), targets.capture(t(2900).minusNanos(1)))
        assertNull(targets.capture(t(2900)))
        assertEquals(t(400), targets.next(t(-1)))
        assertEquals(start, targets.previous(t(401)))
        assertNull(targets.next(t(2000)))
    }

    @Test fun animationIncludesHalfUnitAndSevenDays() {
        val sevenDays = start.plus(Duration.ofDays(7))
        assertTrue(shouldAnimateTimelineMove(t(100), start, false))
        assertFalse(shouldAnimateTimelineMove(t(100).minusNanos(1), start, false))
        assertTrue(shouldAnimateTimelineMove(start, sevenDays, false))
        assertFalse(shouldAnimateTimelineMove(start, sevenDays.plusNanos(1), false))
        assertFalse(shouldAnimateTimelineMove(start, t(500), true))
        assertFalse(shouldAnimateTimelineMove(start, t(500), false, alwaysDirect = true))
        assertFalse(shouldAnimateTimelineMove(start, t(500), false, startsInCoverage = false))
    }

    @Test fun tideIncludesTurnsAndOneFastestMomentPerWarningRun() {
        val tide = PredictionSeries.Tide(emptyList(), listOf(
            TideRatePoint(t(100), 0.6), TideRatePoint(t(200), 0.8),
            TideRatePoint(t(300), -0.59), TideRatePoint(t(400), 0.6),
        ), listOf(TideExtreme(t(600), 0.2, ExtremeKind.LOW), TideExtreme(t(-10), 1.0, ExtremeKind.HIGH)))
        val stops = targets(tide)
        assertEquals(t(200), stops.next(t(-20)))
        assertEquals(t(400), stops.next(t(200)))
        assertEquals(t(600), stops.next(t(400)))
        assertNull(stops.next(t(600)))
    }

    @Test fun measuredWindowNeedsFiveUnitsWhileZerosAndMaximaAlwaysCount() {
        fun current(step: Long): MagneticTargets {
            val points = listOf(-1.0, 0.0, 1.0).mapIndexed { i, speed -> CurrentPoint(t(i * step), speed) }
            return targets(PredictionSeries.Current(points, listOf(
                CurrentEvent(t(step), 0.0, CurrentEventKind.SLACK),
                CurrentEvent(t(2 * step), 1.0, CurrentEventKind.MAX_FLOOD),
            ), null, null))
        }
        val drawn = current(1000)
        assertEquals(t(500), drawn.next(start))
        assertEquals(t(1000), drawn.next(t(500)))
        assertEquals(t(1500), drawn.next(t(1000)))
        assertEquals(t(2000), drawn.next(t(1500)))
        val short = current(998)
        assertEquals(t(998), short.next(start))
        assertEquals(t(1996), short.next(t(998)))
    }

    @Test fun drawnRunClippedAtFirstSampleKeepsBothStrokeEdgesAndTheZero() {
        val step = 900L
        val points = listOf(-0.2, 0.0, 0.25, 0.75).mapIndexed { i, speed -> CurrentPoint(t(i * step), speed) }
        val stops = targets(PredictionSeries.Current(points, listOf(
            CurrentEvent(t(step), 0.0, CurrentEventKind.SLACK)
        ), null, null))
        assertEquals(start, stops.previous(t(step)))
        assertEquals(t(step), stops.next(start))
        assertEquals(t(2250), stops.next(t(step)))
    }

    @Test fun finalAlmanacDayHasOnlyItsRiseAndSetEvenWhenWindowExtendsPastCoverage() {
        val limit = Instant.parse("2101-01-01T00:00:00Z")
        val window = TimelineWindow(limit.minusSeconds(86400), limit.plusSeconds(86400))
        val observer = Observer(32.78, -96.8)
        val expected = sunEvents(window.start, limit, observer)
            .filter { it.kind == SunEventKind.RISE || it.kind == SunEventKind.SET }.map { it.time }
        val stops = MagneticTargets.from(window, PredictionSeries.Derived(emptyList()), 0.0, 32.78, -96.8, true)
        assertEquals(2, expected.size)
        assertEquals(expected, generateSequence(stops.next(window.start)) { stops.next(it) }.toList())
    }

    @Test fun onlineSampleReversalAndBothMaximaAreStops() {
        val points = listOf(-2.0, -1.0, 1.0, 2.0).mapIndexed { i, speed -> CurrentPoint(t(i * 900L), speed) }
        val block = BoundedPredictions.current(StationId("current:online"), TimeSpan(start, t(2700)),
            points, 30.0, 210.0) as PredictionResult.Ready
        val stops = targets(block.series)
        assertEquals(start, stops.next(t(-2)))
        assertEquals(t(1350), stops.next(start))
        assertEquals(t(2700), stops.next(t(1350)))
    }

    @Test fun lunarPeakIsIncludedEvenWhenContactsAreOutsideWindow() {
        val night = Instant.parse("2025-03-14T00:00:00Z")
        val eclipse = lunarEclipses(night, night.plusSeconds(86400)).single()
        val instant = TimelineWindow(eclipse.peak, eclipse.peak)
        val stops = MagneticTargets.from(instant, PredictionSeries.Derived(emptyList()), 0.0, 39.74, -104.99, false)
        assertEquals(eclipse.peak, stops.capture(eclipse.peak))
    }

    @Test fun skySwitchKeepsCoincidentWaterTargetAndGreatestEclipse() {
        val observer = Observer(32.78, -96.8)
        val sun = sunEvents(start, window.end, observer).filter { it.kind in setOf(SunEventKind.RISE, SunEventKind.SET) }
        val peak = solarEclipses(start, window.end, observer).single().peak
        val derived = PredictionSeries.Derived(listOf(PredictionSeries.DerivedSlack(sun.first().time, ExtremeKind.HIGH)))
        fun stops(showSky: Boolean): List<Instant> {
            val targets = MagneticTargets.from(window, derived, 0.5, 32.78, -96.8, showSky)
            return generateSequence(targets.next(start)) { targets.next(it) }.toList()
        }
        assertEquals((sun.map { it.time } + peak).sorted(), stops(true))
        assertEquals(listOf(sun.first().time, peak).sorted(), stops(false))
    }
}
