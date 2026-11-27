package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.CurrentStation
import io.openwaters.slackwater.HarmonicConstituent
import io.openwaters.slackwater.android.core.prediction.CurrentGateEvidence
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.iwls.ChsChunk
import io.openwaters.slackwater.iwls.ChsSample
import io.openwaters.slackwater.iwls.IwlsFetch
import io.openwaters.slackwater.iwls.IwlsSampleGrid
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CanadianCurrentFitTest {
    private val gate = StationIdentity(StationId("gate"), "Gate", "", 49.0, -123.0,
        StationKind.CanadianCurrent, timezone = "America/Vancouver")
    private val now = Instant.parse("2026-11-01T20:12:00Z")
    private val end = Instant.parse("2026-11-01T07:00:00Z")
    private val reference = CurrentStation(listOf(HarmonicConstituent("M2", 2.0, 25.0),
        HarmonicConstituent("K1", 0.4, 300.0)), 30.0, 210.0, 0.2)

    private fun checkWindow(days: Int, early: Boolean) = runBlocking {
        val evidence = CurrentGateEvidence(gate.id, days.toDouble(), ChsCurrentFit.STAMP,
            provisionalSlackMinutes = if (early) 25 else null)
        val requests = mutableListOf<Pair<String, ChsChunk>>()
        val plan = IwlsFetch.chunkPlan(days.toDouble(), end)
        val stages = mutableListOf<CanadianCurrentModel>()
        val result = fitCanadianCurrentWindow(gate, evidence, now, 30.0, 210.0, { code, chunk, grid ->
            requests.add(code to chunk)
            assertEquals(IwlsSampleGrid(15), grid)
            reference.speeds(chunk.start, chunk.end, 900.0).map {
                ChsSample(it.time.toEpochMilli(), if (code == "wcsp1") abs(it.speedKnots)
                    else if (it.speedKnots >= 0) 30.0 else 210.0)
            }
        }) { model ->
            assertEquals(IwlsFetch.chunkPlan(60.0, end).size * 2, requests.size)
            stages.add(model)
        }
        assertEquals(plan.flatMap { listOf("wcsp1" to it, "wcdp1" to it) }, requests)
        assertEquals(if (early) listOf(60.0) else emptyList(), stages.map { it.fitDays })
        assertEquals(days.toDouble(), result.fitDays, 0.0)
        val until = end.plusSeconds(86400)
        val expected = reference.speeds(end, until, 900.0)
        for (model in stages + result) {
            assertNotNull(evidence.compatibleFit(model.station.value, model.recipe, model.fitDays))
            val predicted = CurrentStation(model.harmonics.constituents, model.floodDirection,
                model.ebbDirection, model.harmonics.offset).speeds(end, until, 900.0)
            assertEquals(expected.size, predicted.size)
            for ((want, got) in expected.zip(predicted)) assertEquals(want.speedKnots, got.speedKnots, 0.01)
        }
    }

    @Test fun shortFinal() = checkWindow(60, false)
    @Test fun longFinalOnly() = checkWindow(210, false)
    @Test fun authorizedShortFitPrecedesLongFinal() = checkWindow(210, true)
}
