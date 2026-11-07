package io.openwaters.slackwater.android.data

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.ExtremeKind
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
    @Test fun reopenedOnlineStoreReadsDisjointWeeksAndRepairsMalformedRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "reopened-online-${UUID.randomUUID()}")
        val id = StationId("chs-arran-rapids")
        val today = Instant.parse("2026-07-15T00:00:00Z")
        fun week(start: Instant) = OnlineWindowBlock(
            listOf(CurrentPoint(start, -1.0), CurrentPoint(start.plusSeconds(900), 1.0)), 20.0, 215.0)
        val first = week(today)
        val second = week(today.plusSeconds(7 * 86400L))
        try {
            val writer = OnlineWindowStore(ModelStore(dir))
            writer.save(id, first, today)
            writer.save(id, second, today)
            val models = ModelStore(dir)
            val reopened = OnlineWindowStore(models)
            assertEquals(listOf(first.span, second.span), reopened.coverage(id))
            val repo = AndroidPredictionRepository(context, models)
            for (block in listOf(first, second)) {
                val ready = repo.load(id, block.span, PredictionPurpose.QUERY) as PredictionResult.Ready
                assertEquals(block.points, (ready.series as PredictionSeries.Current).points)
            }
            val hole = today.plusSeconds(86400)
            assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.OUTSIDE_COVERAGE),
                repo.load(id, TimeSpan(hole, hole), PredictionPurpose.QUERY))
            models.write(id, ModelKind.ONLINE_WINDOW, JSONObject().put("blocks", "invalid"))
            assertTrue(models.read(id, ModelKind.ONLINE_WINDOW) is StoredModel.Ready)
            assertTrue(reopened.coverage(id).isEmpty())
            reopened.save(id, second, today)
            assertEquals(listOf(second.span), reopened.coverage(id))

            dir.listFiles()!!.single().writeText("{")
            assertEquals(StoredModel.Unavailable(StoredModel.Unavailable.Reason.CORRUPT),
                models.read(id, ModelKind.ONLINE_WINDOW))
            reopened.save(id, first, today)
            assertEquals(listOf(first.span), reopened.coverage(id))
        } finally { dir.deleteRecursively() }
        Unit
    }

    @Test fun onlineWeeksMergeBySampleTimeAndAgeOutOnlyOnSave() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "online-${UUID.randomUUID()}")
        val models = ModelStore(dir)
        val store = OnlineWindowStore(models)
        val repo = AndroidPredictionRepository(context, models)
        val id = StationId("chs-arran-rapids")
        val today = Instant.parse("2026-07-15T00:00:00Z")
        fun block(start: Instant, vararg speeds: Double) = OnlineWindowBlock(
            speeds.mapIndexed { i, speed -> CurrentPoint(start.plusSeconds(i * 600L), speed) }, 22.0, 215.0)
        try {
            val old = block(today.minusSeconds(90 * 86400L), 1.0, 2.0)
            store.save(id, old, today)
            assertTrue(store.covers(id, old.span))
            val boundary = today.minusSeconds(60 * 86400L)
            store.save(id, block(boundary.minusSeconds(600), 1.0, 2.0, 3.0), today)
            val newer = block(today.plusSeconds(1200), -1.0, 0.0, 0.0, 2.0)
            store.save(id, newer, today)
            val first = block(today, 2.0, 1.0, -3.0)
            store.save(id, first, today)
            val span = TimeSpan(today, newer.span.end)
            assertEquals(listOf(TimeSpan(boundary, boundary.plusSeconds(600)), span), store.coverage(id))
            assertFalse(store.covers(id, TimeSpan(boundary, today)))
            assertFalse(store.covers(id, TimeSpan(today, newer.span.end.plusSeconds(1))))
            val result = repo.load(id, span, PredictionPurpose.QUERY) as PredictionResult.Ready
            val current = result.series as PredictionSeries.Current
            assertEquals(22.0, current.floodBearing!!, 0.0)
            assertEquals(215.0, current.ebbBearing!!, 0.0)
            assertEquals(listOf(2.0, 1.0, -3.0, 0.0, 0.0, 2.0), current.points.map { it.speedKnots })
            assertEquals(listOf(CurrentEventKind.MAX_FLOOD, CurrentEventKind.SLACK, CurrentEventKind.MAX_EBB,
                CurrentEventKind.SLACK, CurrentEventKind.MAX_FLOOD), current.events.map { it.kind })
            assertEquals(listOf(today.plusSeconds(750), today.plusSeconds(1800)),
                current.events.filter { it.kind == CurrentEventKind.SLACK }.map { it.time })
        } finally { dir.deleteRecursively() }
        Unit
    }

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
            val referenceTurns = fit().fittedTide().extremes(span.start.minusSeconds(35 * 60L), span.end.minusSeconds(25 * 60L))
            val expected = referenceTurns.map { turn ->
                val lagMinutes = if (turn.kind == ExtremeKind.HIGH) 25 else 35
                PredictionSeries.DerivedSlack(turn.time.plusSeconds(lagMinutes * 60L), turn.kind)
            }.filter { it.time in span }
            assertEquals(expected, derived.slacks)
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
                val expectedBound = when (kind) {
                    StationCapabilities.BOUNDED_CURRENT -> 45
                    StationCapabilities.PROVISIONAL_CURRENT -> 35
                    else -> null
                }
                for (purpose in PredictionPurpose.entries) {
                    val result = load(id, purpose)
                    if (purpose == PredictionPurpose.QUERY && kind == StationCapabilities.PROVISIONAL_CURRENT) {
                        assertEquals(PredictionResult.Unavailable(PredictionResult.Reason.UNSUPPORTED_PURPOSE), result)
                    } else {
                        assertTrue("$id for $purpose", result is PredictionResult.Ready)
                        result as PredictionResult.Ready
                        assertEquals(kind, result.capabilities)
                        assertEquals(expectedBound, result.slackErrorBoundMinutes)
                    }
                }
                val incompatible = listOf(
                    JSONObject(record.toString()).apply { remove("recipe") },
                    JSONObject(record.toString()).apply { remove("fitDays") },
                    JSONObject(record.toString()).put("recipe", "obsolete"),
                    JSONObject(record.toString()).put("fitDays", 90),
                )
                for (candidate in incompatible) {
                    store.write(StationId(id), ModelKind.CURRENT, candidate)
                    for (purpose in PredictionPurpose.entries) {
                        assertEquals("$id for $purpose", PredictionResult.Unavailable(PredictionResult.Reason.INVALID_MODEL),
                            load(id, purpose))
                    }
                }
            }
            val online = "chs-arran-rapids"
            val block = JSONObject().put("times",JSONArray(listOf(start.epochSecond,start.epochSecond+900,start.epochSecond+3600)))
                .put("speeds",JSONArray(listOf(-1.0,1.0,2.0))).put("floodDirection",20).put("ebbDirection",215)
            store.write(StationId(online),ModelKind.ONLINE_WINDOW,block)
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
