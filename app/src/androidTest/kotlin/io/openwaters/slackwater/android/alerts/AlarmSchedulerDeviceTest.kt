package io.openwaters.slackwater.android.alerts

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import io.openwaters.slackwater.android.core.planning.AlertOccurrence
import io.openwaters.slackwater.android.core.planning.DeliveryPlan
import java.time.Instant
import java.util.UUID
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AlarmSchedulerDeviceTest {
    @Test fun alarmIdentityUsesDistinctOccurrenceUrisAndImmutableExplicitBroadcasts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val a = AlarmScheduler.intent(context, "event/one")
        val b = AlarmScheduler.intent(context, "event/two")
        fun token(intent: Intent) = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val first = token(a)
        val second = token(b)
        try {
            assertEquals("event/one", AlarmScheduler.occurrenceKey(a))
            assertNull(AlarmScheduler.occurrenceKey(Intent(a).setAction(Intent.ACTION_VIEW)))
            assertEquals(AlertReceiver::class.java.name, a.component!!.className)
            assertEquals(first, token(AlarmScheduler.intent(context, "event/one")))
            assertNotEquals(first, second)
        } finally {
            first.cancel()
            second.cancel()
        }
        AlarmScheduler.get(context).access()
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertNotNull(notifications.getNotificationChannel(AlarmScheduler.CHANNEL_ID))
    }

    @Test fun repeatRestoreAndPermissionDenialCancelOnlyTrackedAlarms() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "alarm-test-${UUID.randomUUID()}"
        val inventory = app.getSharedPreferences(file, Context.MODE_PRIVATE)
        val scheduler = AlarmScheduler(app, inventory)
        val now = Instant.now()
        val event = now.plusSeconds(7200)
        val entry = AlertOccurrence(UUID.randomUUID(), event, now.plusSeconds(3600))
        val plan = DeliveryPlan(event, emptyList(), listOf(entry), emptyList())
        val approximate = AlarmAccess(true, false)
        fun pending() = PendingIntent.getBroadcast(app, 0, AlarmScheduler.intent(app, entry.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        try {
            scheduler.applyPlan(plan, now, approximate)
            val first = pending()
            assertNotNull(first)
            AlarmScheduler(app, inventory).applyPlan(plan, now, approximate)
            assertEquals(first, pending())
            scheduler.applyPlan(plan, entry.fire.plusSeconds(1), approximate)
            assertEquals("A delayed OS alarm keeps its original token", first, pending())
            scheduler.applyPlan(plan, now, approximate.copy(notificationsAllowed = false))
            assertNull(pending())
            scheduler.applyPlan(plan, now, approximate)
            scheduler.applyPlan(null, now, approximate)
            assertNull(pending())
        } finally {
            scheduler.applyPlan(null, now, approximate)
            app.deleteSharedPreferences(file)
        }
    }
}
