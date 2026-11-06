package io.openwaters.slackwater.android.data

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class AndroidPredictionRepositoryTest {
    @Test fun everyCapabilityUsesPackagedDataOrACompatibleLocalRecord() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir,"predictions-${UUID.randomUUID()}")
        val store = ModelStore(dir)
        val repo = AndroidPredictionRepository(context,store)
        val start = Instant.parse("2026-07-15T00:00:00Z")
        val span = TimeSpan(start,start.plusSeconds(86400))
        suspend fun load(id: String, purpose: PredictionPurpose = PredictionPurpose.DISPLAY, range: TimeSpan = span) =
            repo.load(StationId(id),range,purpose)
        suspend fun ready(id: String, capability: StationCapabilities): PredictionResult.Ready {
            val result = load(id) as PredictionResult.Ready
            assertEquals(capability,result.capabilities)
            return result
        }
        fun fit() = JSONObject("""{"offset":1.387,"constituents":[{"name":"M2","amplitude":1.5,"phase":10}]}""")
        try {
            ready("noaa/9449880",StationCapabilities.HARMONIC_TIDE)
            ready("noaa/TEC4635",StationCapabilities.SUBORDINATE_TIDE)
            ready("current:noaa/CHB9904",StationCapabilities.HARMONIC_CURRENT)
            ready("current:noaa/PCT3896",StationCapabilities.SUBORDINATE_CURRENT)
            val port = "chs-point-atkinson"
            assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.MISSING_DOWNLOAD),load(port))
            val samples = JSONArray()
            for (i in 0..3) samples.put(JSONObject().put("time",start.plusSeconds(i*900L).toString()).put("height",i.toDouble()))
            store.write(StationId(port),ModelKind.TIDE_PREVIEW,JSONObject().put("samples",samples))
            assertEquals(start.plusSeconds(2700),ready(port,StationCapabilities.TIDE_PREVIEW).coverage.end)
            assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.UNSUPPORTED_PURPOSE),load(port,PredictionPurpose.ALERT))
            store.write(StationId(port),ModelKind.TIDE,JSONObject().put("offset",1.0))
            ready(port,StationCapabilities.TIDE_PREVIEW)
            store.write(StationId(port),ModelKind.TIDE,fit())
            ready(port,StationCapabilities.FITTED_TIDE)
            val derived = ready("chs-malibu-rapids",StationCapabilities.DERIVED_TIMING).series as PredictionSeries.Derived
            assertEquals(2,derived.slacks.map { it.referenceTurn }.distinct().size)
            assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.UNSUPPORTED_PURPOSE),load("chs-malibu-rapids",PredictionPurpose.QUERY))
            val fits = listOf(
                Triple("chs-blackney-passage",210,StationCapabilities.BOUNDED_CURRENT),
                Triple("chs-johnstone-strait-central",60,StationCapabilities.PROVISIONAL_CURRENT),
                Triple("chs-active-pass",60,StationCapabilities.FINAL_CURRENT),
            )
            for ((id,days,kind) in fits) {
                val record = fit().put("recipe",ChsCurrentFit.STAMP).put("fitDays",days)
                    .put("floodDirection",20).put("ebbDirection",215)
                store.write(StationId(id),ModelKind.CURRENT,record)
                ready(id,kind)
                assertTrue(load(id,PredictionPurpose.ALERT) is PredictionResult.Ready)
                if (kind == StationCapabilities.PROVISIONAL_CURRENT) {
                    assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.UNSUPPORTED_PURPOSE),load(id,PredictionPurpose.QUERY))
                }
                store.write(StationId(id),ModelKind.CURRENT,record.put("recipe","obsolete"))
                assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.INVALID_MODEL),load(id))
            }
            val online = "chs-arran-rapids"
            val block = JSONObject().put("times",JSONArray(listOf(start.epochSecond,start.epochSecond+900,start.epochSecond+3600)))
                .put("speeds",JSONArray(listOf(-1.0,1.0,2.0))).put("floodDirection",20).put("ebbDirection",215)
            store.write(StationId(online),ModelKind.ONLINE_WINDOW,JSONObject().put("blocks",JSONArray().put(block)))
            val windows = (load(online) as PredictionResult.Disjoint).blocks
            assertEquals(2,windows.size)
            assertTrue(windows.all { it.capabilities == StationCapabilities.ONLINE_CURRENT })
            assertEquals(start.plusSeconds(900),windows.first().coverage.end)
            assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.OUTSIDE_COVERAGE),load(online,PredictionPurpose.QUERY))
            assertTrue(load(online,PredictionPurpose.QUERY,TimeSpan(start,start.plusSeconds(900))) is PredictionResult.Ready)
            assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.OUTSIDE_COVERAGE),load(online,PredictionPurpose.WIDGET))
            val hole = start.plusSeconds(1800)
            assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.OUTSIDE_COVERAGE),load(online,range=TimeSpan(hole,hole)))
            assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.UNSUPPORTED_PURPOSE),load(online,PredictionPurpose.ALERT))
            val fill = JSONObject().put("times",JSONArray(listOf(start.epochSecond+900,start.epochSecond+1800,start.epochSecond+2700)))
                .put("speeds",JSONArray(listOf(2.0,3.0,1.5))).put("floodDirection",20).put("ebbDirection",215)
            store.write(StationId(online),ModelKind.ONLINE_WINDOW,JSONObject().put("blocks",JSONArray().put(block).put(fill)))
            val complete = load(online,PredictionPurpose.WIDGET,TimeSpan(start,start.plusSeconds(3600))) as PredictionResult.Ready
            val points = (complete.series as PredictionSeries.Current).points
            assertEquals(5,points.size)
            assertEquals(2.0,points[1].speedKnots,0.0)
        } finally { dir.deleteRecursively() }
        Unit
    }
}
