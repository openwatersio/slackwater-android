package io.openwaters.slackwater.android.core.prediction

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.settings.ReadingSettings
import io.openwaters.slackwater.android.core.station.StationId
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CurrentWindowsTest {
    private fun t(s: Long) = Instant.ofEpochSecond(s)
    private fun series(speeds: List<Double>, vararg slacks: Long) = PredictionSeries.Current(
        speeds.mapIndexed { i, v -> CurrentPoint(t(i * 100L),v) },
        slacks.map { CurrentEvent(t(it),0.0,CurrentEventKind.SLACK) }, 30.0, 225.0)
    private val step = Duration.ofSeconds(100)

    @Test fun weakWaterIsNotSlack() {
        assertTrue(series(listOf(1.0,0.1,1.0)).windows(0.5,step).isEmpty())
    }
    @Test fun reversalOnEntryStepCounts() {
        val run = series(listOf(1.0,-0.25,-0.25,-1.0),80).windows(0.5,step).single()
        assertEquals(t(40),run.start)
        assertEquals(listOf(t(80)),run.slacks)
        assertFalse(run.clipped)
    }
    @Test fun aboveThresholdExcursionSplitsRun() {
        val runs = series(listOf(-1.0,0.0,1.0,0.0,-1.0),100,300).windows(0.5,step)
        assertEquals(listOf(t(50),t(250)),runs.map { it.start })
    }
    @Test fun clippedRunHasNoDuration() {
        assertNull(series(listOf(-0.2,0.0,0.2,1.0),100).windows(0.5,step).single().duration)
    }
    @Test fun offLeavesOnlySlackInstant() {
        val run = series(listOf(-1.0,0.0,1.0),100).windows(0.0,step).single()
        assertEquals(t(100),run.start)
        assertEquals(run.start,run.end)
        assertNull(run.duration)
    }
    @Test fun continuousRunRetainsBothExactSlacks() {
        val run = series(listOf(-1.0,-0.1,0.1,-0.1,-1.0),150,250).windows(0.5,step).single()
        assertEquals(listOf(t(150),t(250)),run.slacks)
    }
    @Test fun vectorLeadAndSignedPinAreIndependent() {
        val reading = PredictionSeries.Current(listOf(CurrentPoint(t(0),0.0,0.7,99.0)),
            listOf(CurrentEvent(t(0),0.0,CurrentEventKind.SLACK)),30.0,225.0)
        assertEquals(0.0,reading.pinSpeedKnots(t(0))!!,0.0)
        assertEquals(0.7,reading.readingAt(t(0))!!.magnitudeKnots,0.0)
        assertEquals(99.0,reading.readingAt(t(0))!!.directionDegrees!!,0.0)
        assertEquals(225.0,series(listOf(-1.0,1.0)).readingAt(t(0))!!.directionDegrees!!,0.0)
    }
    @Test fun stationThresholdUsesGlobalAndDeltaWithinRange() {
        val id = StationId("current:noaa/A:B")
        assertEquals(0.5,ReadingSettings().effectiveThresholdKn(id),0.0)
        assertEquals(0.2,ReadingSettings(0.5,mapOf(id to -0.3)).effectiveThresholdKn(id),0.0)
        assertEquals(10.0,ReadingSettings(9.0,mapOf(id to 3.0)).effectiveThresholdKn(id),0.0)
        assertEquals(0.0,ReadingSettings(0.0,mapOf(id to 3.0)).effectiveThresholdKn(id),0.0)
    }
}
