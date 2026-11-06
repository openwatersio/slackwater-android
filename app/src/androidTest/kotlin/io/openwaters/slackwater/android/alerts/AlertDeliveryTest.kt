package io.openwaters.slackwater.android.alerts

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.CurrentEvent
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import java.io.File
import java.time.Clock
import java.time.ZoneId
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AlertDeliveryTest {
    private val start = Instant.parse("2026-11-06T12:00:00Z")
    private val station = StationIdentity(StationId("fixture"), "Fixture", "", 49.0, -123.0, StationKind.Current)
    private val catalog = object : StationCatalog {
        override suspend fun identities() = listOf(station)
        override suspend fun resolve(id: StationId) = StationResolution.Live(station)
    }
    private val paid = ReminderEntitlement.Purchased

    @Test fun validatesCurrentModelThresholdLeadAndAccessBeforeDelivery() = runBlocking {
        val rule = AlertRule(stationId = station.id, trigger = AlertTrigger.SlackWindowOpens, leadSeconds = 60)
        val event = start.plusSeconds(180)
        val expected = AlertOccurrence(rule.id, event, event.minusSeconds(60))
        var speed = 2.0
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
                val series = PredictionSeries.Current(
                    listOf(CurrentPoint(start, -speed), CurrentPoint(start.plusSeconds(600), speed)),
                    listOf(CurrentEvent(start.plusSeconds(300), 0.0, CurrentEventKind.SLACK)), null, null,
                )
                return PredictionResult.Ready(id, span, "fixture", StationCapabilities.HARMONIC_CURRENT, series)
            }
        }
        val verifier = AlertDeliveryVerifier(repository, catalog)
        suspend fun check(candidate: AlertRule? = rule, threshold: Double = 0.5, now: Instant = expected.fire,
                          access: ReminderEntitlement = paid) =
            verifier.stationFor(expected, candidate, threshold, now, access)
        assertEquals(station, check())
        assertNull(check(null))
        assertNull(check(rule.copy(enabled = false)))
        assertNull(check(rule.copy(leadSeconds = 120)))
        assertNull(check(threshold = 0.0))
        assertNull(check(now = expected.event.plusMillis(1)))
        assertNull(check(now = expected.fire.minusMillis(1)))
        assertNull(check(access = ReminderEntitlement.FreeDay(expected.fire)))
        assertNull(check(access = ReminderEntitlement.Free))
        speed = 0.75
        assertNull(check())
    }

    @Test fun receiverPostsStationAndEventTapWithoutAnActivity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val id = StationId("noaa/9449880")
        val rule = AlertRule(stationId = id, trigger = AlertTrigger.TideExtreme(high = true), leadSeconds = 86400)
        val access = ReminderAccessStore.get(app)
        val priorAccess = access.current()
        val rules = AlertRuleStore.get(app)
        val plans = DeliveryPlanStore.get(app)
        val now = Instant.now()
        val span = TimeSpan(now.plusSeconds(60), now.plusSeconds(86400))
        val entry = OccurrenceResolver.resolve(rule, AndroidPredictionRepository(app), span, ZoneOffset.UTC,
            station.latitude, station.longitude, 0.5).first()
        try {
            access.update(paid)
            val reconciliation = AlertReconciliation.get(app)
            assertTrue(reconciliation.save(rule.copy(once = entry.event)))
            withTimeout(30000) {
                while (manager.activeNotifications.none { it.tag == entry.key } &&
                    plans.plan.first()?.notifications.orEmpty().none { it.key == entry.key }) delay(50)
            }
            // Dispatch even if Android already woke the receiver; the duplicate must be harmless.
            app.sendBroadcast(AlarmScheduler.intent(app, entry.key))
            val posted = withTimeout(15000) {
                while (manager.activeNotifications.none { it.tag == entry.key }) delay(50)
                manager.activeNotifications.single { it.tag == entry.key }
            }
            val destination = AlertDestination.intent(app, id, entry.event)
            val target = PendingIntent.getActivity(app, 0, destination, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            assertNotNull(target)
            assertEquals(target, posted.notification.contentIntent)
            assertEquals(AlertDestination(id, entry.event), AlertDestination.read(destination))
            assertFalse(plans.plan.first()!!.notifications.any { it.key == entry.key })
        } finally {
            manager.cancel(entry.key, 1)
            rules.remove(rule.id)
            access.update(priorAccess)
            plans.refill(setOf(entry.key), Instant.now(), priorAccess)
            AlarmScheduler.get(app).syncLatest()
        }
    }

    @Test fun zeroLeadPostsDuringItsMinuteAndInputEditsCannotCommitMidDelivery() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val id = StationId("noaa/9449880")
        val template = AlertRule(stationId = id, trigger = AlertTrigger.TideExtreme(high = true))
        val now = Instant.now()
        val event = OccurrenceResolver.resolve(template, AndroidPredictionRepository(app),
            TimeSpan(now.plusSeconds(120), now.plusSeconds(86400)), ZoneOffset.UTC, 48.0, -123.0, 0.5).first()
        val rule = template.copy(once = event.event)
        val access = ReminderAccessStore.get(app)
        val prior = access.current()
        val rules = AlertRuleStore.get(app)
        val plans = DeliveryPlanStore.get(app)
        val validating = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant {
                validating.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
                return event.event.plusMillis(1)
            }
        }
        try {
            access.update(paid)
            assertTrue(AlertReconciliation.get(app).save(rule))
            withTimeout(30000) { plans.plan.first { it?.notifications?.contains(event) == true } }
            coroutineScope {
                val receiving = async(Dispatchers.IO) { AlertReceiver().deliver(app, event.key, clock) }
                assertTrue(validating.await(10, TimeUnit.SECONDS))
                val deleting = async(start = CoroutineStart.UNDISPATCHED) { rules.remove(rule.id) }
                try {
                    assertFalse(deleting.isCompleted)
                    assertTrue(rules.rules.first().any { it.id == rule.id })
                } finally { resume.countDown() }
                receiving.await()
                deleting.await()
            }
            assertTrue(manager.activeNotifications.any { it.tag == event.key })
            manager.cancel(event.key, 1)
            // Even a fresh plan built at an earlier instant cannot revive the consumed minute.
            plans.rebuild(listOf(rule), listOf(event), emptyList(), event.event, paid)
            assertFalse(plans.plan.first()!!.notifications.contains(event))
        } finally {
            resume.countDown()
            manager.cancel(event.key, 1)
            rules.remove(rule.id)
            access.update(prior)
            AlarmScheduler.get(app).syncLatest()
        }
    }

    @Test fun simultaneousBroadcastsClaimOnceAndKeepOtherDueAlarms() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(app.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        try {
            val store = DeliveryPlanStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
            val rule = AlertRule(stationId = station.id, trigger = AlertTrigger.Slack, leadSeconds = 600)
            val second = rule.copy(id = UUID.randomUUID(), stationId = StationId("other"))
            val event = start.plusSeconds(600)
            val firstEntry = AlertOccurrence(rule.id, event, start)
            val otherEntry = firstEntry.copy(ruleId = second.id)
            store.rebuild(listOf(rule, second), listOf(firstEntry, otherEntry), emptyList(), start, paid)
            val claims = coroutineScope {
                List(2) { async { store.consume(firstEntry, start.plusSeconds(1), paid, valid = true) } }.awaitAll()
            }
            assertEquals(1, claims.count { it })
            assertEquals(listOf(otherEntry), store.plan.first()!!.notifications)
            assertFalse(store.consume(otherEntry, start.plusSeconds(1), ReminderEntitlement.FreeDay(start), valid = true))
            assertTrue(store.plan.first()!!.notifications.isEmpty())
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }
}
