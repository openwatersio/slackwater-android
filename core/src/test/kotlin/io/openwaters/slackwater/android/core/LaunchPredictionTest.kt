package io.openwaters.slackwater.android.core
import io.openwaters.slackwater.HarmonicConstituent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test
import org.junit.Assert.*
class LaunchPredictionTest {
    @Test fun matchesUpstreamFixtures() { FixtureSmoke.verify() }
    @Test fun usesInjectedClockAndDatum() {
        val reading = launchReading(emptyList(), 1.387, 48.4284, -123.3656, Clock.fixed(Instant.ofEpochMilli(-631152000000L), ZoneOffset.UTC))
        assertEquals(1.387, reading.heightMetres, 1e-9)
        assertEquals(3.084280, reading.sunAltitudeDegrees, 0.00001)
    }
}
