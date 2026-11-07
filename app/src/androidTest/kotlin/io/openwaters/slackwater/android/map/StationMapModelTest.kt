package io.openwaters.slackwater.android.map

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.android.core.map.PinBox
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationSeries
import io.openwaters.slackwater.android.data.ModelKind
import io.openwaters.slackwater.android.data.ModelStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StationMapModelTest {
    @Test fun restrictedGaugeHasAnExplanatoryRingAndNeverGetsPredicted() = runBlocking {
        val model = StationMapModel(InstrumentationRegistry.getInstrumentation().targetContext)
        val pins = model.pins(PinBox(38.257, 22.076, 38.258, 22.077), 12.0, StationSeries.Tide, null, Instant.EPOCH)
        val gauge = pins.single { it.station.id == StationId("ticon/aigio-aig-grc-cmems") }
        assertEquals("TICON-4", gauge.station.source)
        assertEquals("unknown", gauge.state.state)
        assertNull(gauge.state.reading)
    }

    @Test fun currentPinStopsShowingItsReadingWhenTheStoredFitLosesItsRecipe() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "map-fit-${UUID.randomUUID()}")
        val store = ModelStore(directory)
        val map = StationMapModel(context, store)
        val id = StationId("chs-johnstone-strait-central")
        val bounds = PinBox(50.471, -126.137, 50.472, -126.136)
        val at = Instant.parse("2026-07-15T00:00:00Z")
        suspend fun pin() = map.pins(bounds, 12.0, StationSeries.Current, null, at).single { it.station.id == id }
        val record = JSONObject("""{"offset":2,"fitDays":60,"floodDirection":20,"ebbDirection":215,
            "constituents":[{"name":"M2","amplitude":1,"phase":10}]}""").put("recipe", ChsCurrentFit.STAMP)
        try {
            store.write(id, ModelKind.CURRENT, record)
            assertNotNull(pin().state.reading)
            record.remove("recipe")
            store.write(id, ModelKind.CURRENT, record)
            val unavailable = pin().state
            assertEquals("unknown", unavailable.state)
            assertNull(unavailable.reading)
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test fun doddKeepsItsIdentityWhileOnlyACoveredNonzeroVectorGetsAnArrow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "map-${UUID.randomUUID()}")
        val store = ModelStore(directory)
        val model = StationMapModel(context, store)
        val bounds = PinBox(49.135, -123.818, 49.136, -123.817)
        val id = StationId("chs-dodd-narrows")
        val start = Instant.parse("2026-07-15T00:00:00Z")
        suspend fun pin(seconds: Long) = model.pins(bounds, 12.0, StationSeries.Current, null, start.plusSeconds(seconds)).single()
        try {
            val missing = pin(0)
            assertEquals(id, missing.station.id)
            assertEquals("unknown", missing.state.state)
            assertNull(model.currentFields(start).dodd)
            val block = JSONObject().put("times", JSONArray(listOf(start.epochSecond, start.epochSecond + 900)))
                .put("speeds", JSONArray(listOf(1.0, -1.0))).put("floodDirection", 355).put("ebbDirection", 155)
            store.write(id, ModelKind.ONLINE_WINDOW, block)
            assertEquals(355.0, model.currentFields(start).dodd!!.second, 0.0)
            assertNull(model.currentFields(start.plusSeconds(450)).dodd)
            val ebb = pin(900)
            assertEquals(155.0, model.currentFields(start.plusSeconds(900)).dodd!!.second, 0.0)
            assertEquals(missing.state, ebb.state)
            assertNull(model.currentFields(start.plusSeconds(901)).dodd)
            block.remove("ebbDirection")
            block.remove("floodDirection")
            store.write(id, ModelKind.ONLINE_WINDOW, block)
            assertEquals(missing, pin(0))
            assertNull(model.currentFields(start).dodd)
            assertTrue(model.pins(bounds, 12.0, StationSeries.Tide, null, start).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }
}
