package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.ChsTideFit
import io.openwaters.slackwater.HarmonicSample
import io.openwaters.slackwater.fit
import io.openwaters.slackwater.HarmonicConstituent
import io.openwaters.slackwater.iwls.ChsSample
import kotlinx.coroutines.runBlocking
import io.openwaters.slackwater.Station
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
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

    @Test fun sharedWeekEndpointsKeepBothWeightsInTheFit() = runBlocking {
        val now = Instant.parse("2026-03-02T12:00:00Z")
        val supplied = mutableListOf<HarmonicSample>()
        val actual = fitCanadianTideWindow(now, ZoneId.of("America/Vancouver")) { chunk, grid ->
            val step = grid.sampleMinutes * 60L
            (chunk.start.epochSecond..chunk.end.epochSecond step step).map { second ->
                // A pulse at each epoch-week boundary makes dropping a duplicate measurable.
                val height = if (second % 604800L == 0L) 3.0 else 2.0
                supplied += HarmonicSample(Instant.ofEpochSecond(second), height)
                ChsSample(second * 1000, height)
            }
        }
        val ordered = supplied.sortedBy { it.time }
        val weighted = fit(ordered, ChsTideFit.basis)
        val singleWeight = fit(ordered.distinctBy { it.time }, ChsTideFit.basis)
        assertTrue(abs(weighted.rms - singleWeight.rms) > 0.001)
        assertEquals(weighted.rms, actual.rms, 1e-10)
    }
}
