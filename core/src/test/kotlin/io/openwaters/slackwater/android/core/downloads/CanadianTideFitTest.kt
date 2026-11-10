package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.ChsTideFit
import io.openwaters.slackwater.HarmonicConstituent
import io.openwaters.slackwater.iwls.ChsSample
import kotlinx.coroutines.runBlocking
import io.openwaters.slackwater.Station
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CanadianTideFitTest {
    @Test fun sixtyDaysOfQuarterHourlyPredictionsRecoverTheFollowingWeek() = runBlocking {
        val start = Instant.parse("2026-01-01T08:00:00Z")
        val end = start.plus(Duration.ofDays(ChsTideFit.DAYS.toLong()))
        val step = Duration.ofMinutes(ChsTideFit.SAMPLE_MINUTES.toLong())
        val port = Station(listOf(
            HarmonicConstituent("M2", 1.2, 310.0),
            HarmonicConstituent("K1", 0.6, 25.0),
            HarmonicConstituent("O1", 0.4, 180.0),
        ), 2.3)
        val fit = fitCanadianTideWindow(end.plusSeconds(12345), ZoneId.of("America/Vancouver")) { chunk, grid ->
            assertEquals(15, grid.sampleMinutes)
            check(chunk.end <= end)
            port.heights(chunk.start, chunk.end, grid.sampleMinutes * 60.0)
                .drop(1) // IWLS can omit a chunk endpoint.
                .map { ChsSample(it.time.toEpochMilli(), it.heightMetres) }
        }
        val recovered = Station(fit.constituents, fit.offset)
        val next = end.plus(step)
        val until = end.plus(Duration.ofDays(7))
        val expected = port.heights(next, until, step.seconds.toDouble())
        val actual = recovered.heights(next, until, step.seconds.toDouble())
        assertEquals(672, actual.size)
        for ((reading, prediction) in expected.zip(actual)) {
            assertEquals(reading.time, prediction.time)
            assertEquals(reading.time.toString(), reading.heightMetres, prediction.heightMetres, 0.01)
        }
    }
}
