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
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CardReadingTest {
    private val t0 = Instant.parse("2026-06-01T00:00:00Z")
    private val id = StationId("noaa/1")

    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    private fun ready(series: PredictionSeries, capabilities: StationCapabilities, hours: Long = 6) =
        PredictionResult.Ready(id, TimeSpan(t0, at(hours * 60)), "test", capabilities, series)

    // A rising tide from 1 m at t0 to 2 m at 6 h, high water at 6 h.
    private val tide = PredictionSeries.Tide(
        points = (0..36).map { TidePoint(at(it * 10L), 1.0 + it / 36.0) },
        rates = (0..36).map { TideRatePoint(at(it * 10L), 1.0 / 6) },
        events = listOf(TideExtreme(at(360), 2.0, ExtremeKind.HIGH)),
    )

    @Test fun aTideReadsItsHeightAndDirection() {
        val reading = cardReading(ready(tide, StationCapabilities.HARMONIC_TIDE), at(180)) as CardReading.Tide
        assertEquals(1.5, reading.heightMetres, 1e-9)
        assertTrue(reading.rising)
    }

    @Test fun outsideCoverageOrWithoutADownloadThereIsNoReading() {
        assertEquals(CardReading.Unavailable, cardReading(ready(tide, StationCapabilities.HARMONIC_TIDE), at(400)))
        assertEquals(CardReading.NotDownloaded, cardReading(PredictionResult.Unavailable(PredictionResult.Reason.MISSING_DOWNLOAD), t0))
        assertEquals(CardReading.Unavailable, cardReading(PredictionResult.Unavailable(PredictionResult.Reason.INVALID_MODEL), t0))
    }

    @Test fun aDisjointResultReadsTheBlockCoveringTheMoment() {
        val early = ready(tide, StationCapabilities.ONLINE_CURRENT, hours = 2)
        val result = PredictionResult.Disjoint(listOf(early.copy(coverage = TimeSpan(at(240), at(360))), early))
        assertTrue(cardReading(result, at(60)) is CardReading.Tide)
        assertEquals(CardReading.Unavailable, cardReading(result, at(200)))
    }

    // A flood peaking at 2 kn on 090°, slack at 3 h, then ebb on 270°.
    private fun current(cross: Double = 0.0) = PredictionSeries.Current(
        points = (0..36).map {
            val major = 2.0 * Math.cos(Math.PI * it / 36.0)
            CurrentPoint(at(it * 10L), major, Math.hypot(major, cross), if (major >= 0) 90.0 else 270.0)
        },
        events = listOf(
            CurrentEvent(at(0), 2.0, CurrentEventKind.MAX_FLOOD, 90.0),
            CurrentEvent(at(180), 0.0, CurrentEventKind.SLACK),
            CurrentEvent(at(360), -2.0, CurrentEventKind.MAX_EBB, 270.0),
        ),
        floodBearing = 90.0, ebbBearing = 270.0,
    )

    @Test fun aCurrentReadsSpeedStateAndSet() {
        val flooding = cardReading(ready(current(), StationCapabilities.HARMONIC_CURRENT), at(60)) as CardReading.Current
        assertEquals(CardCurrentState.FLOODING, flooding.state)
        assertEquals(2.0 * Math.cos(Math.PI / 6), flooding.speedKnots, 1e-6)
        assertEquals(90.0, flooding.setDegrees!!, 1e-6)
        assertFalse(flooding.approximate)
        val ebbing = cardReading(ready(current(), StationCapabilities.HARMONIC_CURRENT), at(300)) as CardReading.Current
        assertEquals(CardCurrentState.EBBING, ebbing.state)
        assertEquals(270.0, ebbing.setDegrees!!, 1e-6)
    }

    @Test fun slackShowsANeutralDotUnlessCrossFlowStillPrints() {
        val slack = cardReading(ready(current(), StationCapabilities.HARMONIC_CURRENT), at(180)) as CardReading.Current
        assertEquals(CardCurrentState.SLACK, slack.state)
        assertNull(slack.setDegrees)
    }

    @Test fun earlyAndBoundedCanadianModelsAreApproximate() {
        for (capabilities in listOf(StationCapabilities.PROVISIONAL_CURRENT, StationCapabilities.BOUNDED_CURRENT)) {
            assertTrue((cardReading(ready(current(), capabilities), at(60)) as CardReading.Current).approximate)
        }
        assertFalse((cardReading(ready(current(), StationCapabilities.FINAL_CURRENT), at(60)) as CardReading.Current).approximate)
    }

    @Test fun aDerivedGateReadsItsPhase() {
        val slacks = listOf(
            PredictionSeries.DerivedSlack(at(60), ExtremeKind.LOW),
            PredictionSeries.DerivedSlack(at(240), ExtremeKind.HIGH),
        )
        assertEquals(CardCurrentState.SLACK, derivedPhase(slacks, at(72)))
        assertEquals(CardCurrentState.FLOODING, derivedPhase(slacks, at(73)))
        // Toward a low-water slack the water ebbs; past the last high-water slack it ebbs too.
        assertEquals(CardCurrentState.EBBING, derivedPhase(slacks.take(1), at(0)))
        assertEquals(CardCurrentState.EBBING, derivedPhase(slacks, at(300)))
        assertNull(derivedPhase(emptyList(), at(0)))
        val result = ready(PredictionSeries.Derived(slacks), StationCapabilities.DERIVED_TIMING)
        assertEquals(CardReading.Derived(CardCurrentState.FLOODING), cardReading(result, at(120)))
    }

    @Test fun valuesFormatInTheChosenUnits() {
        assertEquals("4.2", formatHeightValue(1.28, imperial = true, Locale.US))
        assertEquals("1.28", formatHeightValue(1.28, imperial = false, Locale.US))
        assertEquals("0.00", formatHeightValue(-0.004, imperial = false, Locale.US))
        assertEquals("-0.01", formatHeightValue(-0.005, imperial = false, Locale.US))
        assertEquals("1,28", formatHeightValue(1.28, imperial = false, Locale.GERMANY))
        assertEquals("1.5", formatSpeedValue(1.45, SpeedUnitKind.KNOTS, Locale.US))
        assertEquals("2.8", formatSpeedValue(1.5, SpeedUnitKind.KILOMETRES_PER_HOUR, Locale.US))
        assertEquals("0.8", formatSpeedValue(1.5, SpeedUnitKind.METRES_PER_SECOND, Locale.US))
        assertEquals("SW", formatSet(225.0, asBearing = false))
        assertEquals("225°", formatSet(225.0, asBearing = true))
        assertEquals("005°", formatSet(4.6, asBearing = true))
        assertEquals("000°", formatSet(359.6, asBearing = true))
    }

    @Test fun theCardSpanCoversOneAndAHalfSwingsBackAndTwoAndAHalfAhead() {
        val span = cardSpan(t0)
        assertEquals(Duration.ofSeconds((6.21 * 3600 * 1.5).toLong()), Duration.between(span.start, t0))
        assertEquals(Duration.ofSeconds((6.21 * 3600 * 2.5).toLong()), Duration.between(t0, span.end))
    }
}
