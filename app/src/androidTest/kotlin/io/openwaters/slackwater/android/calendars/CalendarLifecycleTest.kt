package io.openwaters.slackwater.android.calendars

import android.app.job.JobScheduler
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.android.data.ModelStore
import io.openwaters.slackwater.android.data.ModelKind
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CalendarLifecycleTest {
    @Test fun incompatibleCurrentRecipeClearsOnlyItsPublishedCalendarWithoutAWriteJournal() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        grantCalendarPermissions(app)
        val id = StationId("chs-active-pass")
        val access = ReminderAccessStore.get(app)
        val prior = access.current()
        val repo = CalendarRepository.get(app)
        val writer = ModelStore(File(app.noBackupFilesDir, "models"))
        val record = JSONObject("""{"offset":1.387,"constituents":[{"name":"M2","amplitude":1.5,"phase":10}]}""")
            .put("recipe", ChsCurrentFit.STAMP).put("fitDays", 60).put("floodDirection", 20).put("ebbDirection", 215)
        try {
            access.update(ReminderEntitlement.Purchased)
            writer.write(id, ModelKind.CURRENT, record)
            assertTrue(repo.subscribe(id))
            assertTrue(repo.eventCount(id) > 0)
            assertNotNull(repo.subscriptions.first().single { it.stationId == id }.publishedSource)
            writer.write(id, ModelKind.CURRENT, record.put("recipe", "incompatible"))
            assertTrue(CalendarStateStore.get(app).state.first().invalidations.isEmpty())
            assertTrue(repo.reconcile())
            assertEquals(0, repo.eventCount(id))
            assertTrue(repo.subscriptions.first().any { it.stationId == id })
        } finally {
            repo.unsubscribe(id)
            writer.remove(id, ModelKind.CURRENT)
            access.update(prior)
        }
    }

    @Test fun expiredTrialClearsFutureEventsAndKeepsTheSubscriptionForPurchase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        grantCalendarPermissions(app)
        val id = StationId("noaa/9449880")
        val access = ReminderAccessStore.get(app)
        val prior = access.current()
        val repo = CalendarRepository.get(app)
        try {
            access.update(ReminderEntitlement.FreeDay(Instant.now().plusSeconds(3600)))
            assertFalse(repo.canSubscribe(StationId("unknown/calendar")))
            assertTrue(repo.subscribe(id))
            val active = repo.subscriptions.first().single { it.stationId == id }
            assertEquals(CalendarOrigin.FREE_DAY, active.origin)
            val tasks = app.getSystemService(JobScheduler::class.java).allPendingJobs
                .filter { it.service.className == CalendarRefreshService::class.java.name }
            assertTrue(tasks.any { it.isPeriodic && it.isPersisted })
            assertTrue(tasks.any { !it.isPeriodic && it.isPersisted && it.minLatencyMillis > 0 })
            assertTrue(repo.eventCount(id) > 0)

            access.update(ReminderEntitlement.FreeDay(Instant.now()))
            assertTrue(repo.reconcile())
            val paused = repo.subscriptions.first().single { it.stationId == id }
            assertEquals(active.calendarId, paused.calendarId)
            assertEquals(0, repo.eventCount(id))

            access.update(ReminderEntitlement.Purchased)
            access.update(ReminderEntitlement.Free)
            assertTrue(repo.reconcile())
            assertEquals(CalendarOrigin.PURCHASED, repo.subscriptions.first().single { it.stationId == id }.origin)
            assertTrue(repo.eventCount(id) > 0)
        } finally {
            repo.unsubscribe(id)
            access.update(prior)
        }
    }
}
