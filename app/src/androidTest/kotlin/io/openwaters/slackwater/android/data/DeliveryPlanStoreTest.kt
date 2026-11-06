package io.openwaters.slackwater.android.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.planning.AlertRule
import io.openwaters.slackwater.android.core.planning.AlertTrigger
import io.openwaters.slackwater.android.core.planning.AlertOccurrence
import io.openwaters.slackwater.android.core.planning.DeliveryPlan
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryPlanStoreTest {
    private suspend fun open(file: File, block: suspend (DeliveryPlanStore) -> Unit) {
        val job = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            block(DeliveryPlanStore(data))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun reopenAndRefillKeepTheOriginalHorizonAndCompleteEntries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val now = Instant.parse("2026-11-01T18:00:00.123456789Z")
        val id = UUID.randomUUID()
        val events = (1L..65L).map {
            val event = now.plusSeconds(it * 60)
            AlertOccurrence(id, event, event.minusSeconds(10), end = event.plusSeconds(15))
        }
        val calendar = events.first().copy(end = null, heightM = -0.42, noWindow = true)
        val saved = DeliveryPlan(now.plusSeconds(1_209_600), listOf(calendar), events.take(64), events.drop(64))
        val refilled = saved.copy(notifications = events.drop(1), overflow = emptyList())
        try {
            open(file) { store ->
                assertNull(store.plan.first())
                val rule = AlertRule(id, StationId("test"), AlertTrigger.Slack, leadSeconds = 10)
                store.rebuild(listOf(rule), events, listOf(calendar), now, ReminderEntitlement.Purchased)
            }
            open(file) { store ->
                assertEquals(saved, store.plan.first())
                assertEquals(refilled, store.refill(setOf(events.first().key), events.first().fire, ReminderEntitlement.Purchased))
            }
            open(file) { store -> assertEquals(refilled, store.plan.first()) }
        } finally {
            file.delete()
        }
    }

    @Test fun saveFollowedByAnObserverPassKeepsImmediateReminderUntilDelivery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val now = Instant.parse("2026-11-01T18:00:00Z")
        val event = now.plusSeconds(600)
        val rule = AlertRule(stationId = StationId("test"), trigger = AlertTrigger.Slack, leadSeconds = 1800)
        val original = AlertOccurrence(rule.id, event, event.minusSeconds(rule.leadSeconds))
        val due = original.copy(fire = now, originalFire = original.fire)
        val access = ReminderEntitlement.Purchased
        suspend fun DeliveryPlanStore.pass(at: Instant, saved: Set<UUID> = emptySet()) =
            rebuild(listOf(rule), listOf(original), emptyList(), at, access, saved)
        try {
            open(file) { store -> store.pass(now, setOf(rule.id)) }
            open(file) { store ->
                store.pass(now.plusSeconds(1))
                assertEquals(listOf(due), store.plan.first()!!.notifications)
                store.refill(setOf(due.key), now.plusSeconds(2), access)
            }
            open(file) { store ->
                store.pass(now.plusSeconds(3), setOf(rule.id))
                assertEquals(emptyList<AlertOccurrence>(), store.plan.first()!!.notifications)
            }
        } finally {
            file.delete()
        }
    }
}
