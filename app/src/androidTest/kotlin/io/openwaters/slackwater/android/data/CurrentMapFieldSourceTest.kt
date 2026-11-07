package io.openwaters.slackwater.android.data

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import java.util.UUID
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CurrentMapFieldSourceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val seymour = StationId("chs-seymour-narrows")
    private val dodd = StationId("chs-dodd-narrows")
    private val tacoma = StationId("current:noaa/PUG1527")
    private val time = Instant.parse("2026-11-07T16:00:00Z")

    @Test fun shippedFillAndNoaaPatchWorkOfflineWhileCanadianFlowDependsOnSavedPredictions() = runBlocking {
        val directory = File(context.cacheDir, "fields-${UUID.randomUUID()}")
        val models = ModelStore(directory)
        val source = CurrentMapFieldSource(context, AndroidPredictionRepository(context, models)::currentAt)
        try {
            val initial = source.evaluate(time)
            assertEquals(13168, initial.fill.size)
            assertEquals(56, initial.patches.size)
            assertNull(initial.dodd)

            val fit = JSONObject("""{"offset":2,"fitDays":60,"floodDirection":20,"ebbDirection":215,
                "constituents":[{"name":"M2","amplitude":1,"phase":10}]}""").put("recipe", ChsCurrentFit.STAMP)
            models.write(seymour, ModelKind.CURRENT, fit)
            val window = OnlineWindowBlock(
                listOf(CurrentPoint(time, 1.0), CurrentPoint(time.plusSeconds(900), -1.0)), 355.0, 155.0)
            OnlineWindowStore(models).save(dodd, window, time)
            val covered = source.evaluate(time)
            assertEquals(66, covered.patches.size)
            assertEquals(355.0, requireNotNull(covered.dodd).second, 0.0)
            assertNull(source.evaluate(time.plusSeconds(450)).dodd)
            assertEquals(155.0, requireNotNull(source.evaluate(time.plusSeconds(900)).dodd).second, 0.0)
            assertNull(source.evaluate(time.plusSeconds(901)).dodd)

            models.write(seymour, ModelKind.CURRENT, fit.put("recipe", "old-recipe"))
            assertEquals(56, source.evaluate(time).patches.size)
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test fun mapRequestsSampleTheExactSecondWithoutChangingTheDisplaySeries() = runBlocking {
        val repository = AndroidPredictionRepository(context)
        val selected = time.plusSeconds(97)
        val map = repository.currentAt(tacoma, selected) as PredictionResult.Ready
        val display = repository.load(tacoma, TimeSpan(selected, selected), PredictionPurpose.DISPLAY) as PredictionResult.Ready
        assertEquals(listOf(selected), (map.series as PredictionSeries.Current).points.map { it.time })
        assertEquals(listOf(time, time.plusSeconds(600)), (display.series as PredictionSeries.Current).points.map { it.time })
    }

    @Test fun anOnlineAnchorIsEvaluatedOnceAndCannotPaintThroughAHole() = runBlocking {
        val calls = mutableListOf<StationId>()
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
                calls += id
                return if (id == seymour) BoundedPredictions.current(id, span,
                    listOf(CurrentPoint(time, 2.0), CurrentPoint(time.plusSeconds(1800), 1.0)), 90.0, 220.0)
                else PredictionResult.Unavailable(PredictionResult.Reason.MISSING_DOWNLOAD)
            }
        }
        val source = CurrentMapFieldSource(context) { id, at -> repository.load(id, TimeSpan(at, at), PredictionPurpose.DISPLAY) }
        assertEquals(10, source.evaluate(time).patches.size)
        assertEquals(listOf(dodd, seymour, tacoma).sortedBy { it.value }, calls.sortedBy { it.value })
        calls.clear()
        assertTrue(source.evaluate(time.plusSeconds(900)).patches.isEmpty())
        assertEquals(3, calls.distinct().size)
        assertEquals(3, calls.size)
    }
}
