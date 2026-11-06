package io.openwaters.slackwater.android.calendars

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.*
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run each phase in a new instrumentation process, revoking calendar access for the middle phase. */
class CalendarInvalidationFixture {
    @Test fun stationCleanupSurvivesRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("calendarPhase")
        assumeTrue(phase in listOf("seed", "denied", "deniedLegacy", "restored"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val reference = StationId("chs-point-atkinson")
        val gate = StationId("chs-malibu-rapids")
        val unrelated = StationId("noaa/9449880")
        val newStation = StationId("noaa/TEC4635")
        val models = ModelStore.get(app)
        val repo = CalendarRepository.get(app)
        val state = CalendarStateStore.get(app)
        val access = ReminderAccessStore.get(app)
        when (phase) {
            "seed" -> {
                access.update(ReminderEntitlement.FreeDay(Instant.now().plusSeconds(7200)))
                models.write(reference, ModelKind.TIDE, JSONObject("""{"offset":1.387,"constituents":[{"name":"M2","amplitude":1.5,"phase":10}]}"""))
                for (id in listOf(reference, gate, unrelated)) {
                    assertTrue(repo.subscribe(id))
                    assertTrue(repo.eventCount(id) > 0)
                }
                assertTrue(repo.subscriptions.first().all { it.origin == CalendarOrigin.FREE_DAY })
                access.update(ReminderEntitlement.Purchased)
                access.update(ReminderEntitlement.Free)
                assertTrue(repo.reconcile())
                assertTrue(repo.subscriptions.first().all { it.origin == CalendarOrigin.PURCHASED })
            }
            "denied", "deniedLegacy" -> {
                val writer = if (phase == "deniedLegacy") ModelStore(java.io.File(app.noBackupFilesDir, "models")) else models
                writer.remove(reference, ModelKind.TIDE)
                models.remove(unrelated, ModelKind.ONLINE_WINDOW)
                assertFalse(repo.reconcile())
                assertEquals(setOf(reference, gate), state.state.first().invalidations.keys)
                assertTrue(repo.cleanupPending.first())
                access.update(ReminderEntitlement.Purchased)
                assertFalse(repo.subscribe(newStation))
                assertTrue(repo.subscriptions.first().single { it.stationId == newStation }.pendingWork)
                access.update(ReminderEntitlement.Free)
            }
            "restored" -> {
                repo.reconcile()
                assertTrue(state.state.first().invalidations.isEmpty())
                assertEquals(0, repo.eventCount(reference))
                assertEquals(0, repo.eventCount(gate))
                assertTrue(repo.eventCount(unrelated) > 0)
                assertTrue(repo.eventCount(newStation) > 0)
                val allStations = setOf(reference, gate, unrelated, newStation)
                assertEquals(allStations, repo.subscriptions.first().map { it.stationId }.toSet())
                for (id in allStations) repo.unsubscribe(id)
                assertTrue(repo.subscriptions.first().isEmpty())
            }
        }
    }
}
