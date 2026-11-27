package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.android.core.station.StationId
import org.junit.Assert.*
import org.junit.Test

class StationSubscriptionMigrationTest {
    private val old = StationId("old")
    private val target = StationId("new")
    private val mapping = mapOf(old to target)
    private val existing = StationSubscription(old, calendarId = 42, origin = CalendarOrigin.PURCHASED, pendingWork = false, publishedSource = "old-source")

    @Test fun successorCreatesNewSubscriptionAndJournalsOldCalendarRemoval() {
        val moved = migrateCalendarSubscriptions(listOf(existing), mapping)
        assertEquals(existing.copy(pendingDeletion = true, pendingWork = true), moved.single { it.stationId == old })
        assertEquals(StationSubscription(target, origin = CalendarOrigin.PURCHASED), moved.single { it.stationId == target })
        assertEquals(moved, migrateCalendarSubscriptions(moved, mapping))
    }

    @Test fun successorPendingRemovalDefersMigration() {
        val deleting = StationSubscription(target, calendarId = 55, origin = CalendarOrigin.FREE_DAY, pendingDeletion = true)
        assertEquals(listOf(existing.copy(pendingWork = true), deleting), migrateCalendarSubscriptions(listOf(existing, deleting), mapping))
    }

    @Test fun paidOriginSurvivesAFreeSuccessorCollision() {
        val free = StationSubscription(target, calendarId = 55, origin = CalendarOrigin.FREE_DAY)
        val migrated = migrateCalendarSubscriptions(listOf(existing, free), mapping)
        assertEquals(CalendarOrigin.PURCHASED, migrated.single { it.stationId == target }.origin)
    }

    @Test fun existingSuccessorAndPendingRemovalAreNeverResurrected() {
        val successor = StationSubscription(target, calendarId = 55, origin = CalendarOrigin.PURCHASED)
        assertEquals(listOf(existing.copy(pendingDeletion = true, pendingWork = true), successor),
            migrateCalendarSubscriptions(listOf(existing, successor), mapping))
        val removing = existing.copy(pendingDeletion = true)
        assertEquals(listOf(removing), migrateCalendarSubscriptions(listOf(removing), mapping))
    }
}
