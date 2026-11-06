package io.openwaters.slackwater.android.alerts

import io.openwaters.slackwater.android.core.planning.AlertOccurrence
import io.openwaters.slackwater.android.core.planning.DeliveryPlan
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSchedulerTest {
    @Test fun clockAndTimezoneChangesKeepPendingAlarmsButRebootReinstallsFutureOnes() {
        org.junit.Assert.assertTrue(retainsScheduledAlarms(android.content.Intent.ACTION_TIMEZONE_CHANGED))
        org.junit.Assert.assertTrue(retainsScheduledAlarms(android.content.Intent.ACTION_TIME_CHANGED))
        org.junit.Assert.assertFalse(retainsScheduledAlarms(android.content.Intent.ACTION_BOOT_COMPLETED))
        org.junit.Assert.assertFalse(retainsScheduledAlarms(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val access = AlarmAccess(notificationsAllowed = true, exactAllowed = false)

    @Test fun onlyPendingEntriesWithinTheHorizonScheduleEvenWithoutExactAccess() {
        fun occurrence(fire: Long, event: Long) =
            AlertOccurrence(UUID.randomUUID(), now.plusSeconds(event), now.plusSeconds(fire))
        val future = occurrence(60, 1800)
        val immediate = occurrence(-1, 1200).copy(originalFire = now.minusSeconds(600))
        val due = occurrence(0, 60)
        val entries = listOf(future, immediate, due, occurrence(-20, 300), occurrence(-10, -1), occurrence(61, 400))
        val plan = DeliveryPlan(now.plusSeconds(60), emptyList(), entries, listOf(occurrence(10, 300)))
        val expected = mapOf(future.key to future.fire, immediate.key to now, due.key to now)
        assertEquals(expected, alarmsToSchedule(plan, now, access))
        assertEquals(expected, alarmsToSchedule(plan, now, access.copy(exactAllowed = true)))
        assertEquals(emptyMap<String, Instant>(), alarmsToSchedule(plan, now, access.copy(notificationsAllowed = false)))
    }
}
