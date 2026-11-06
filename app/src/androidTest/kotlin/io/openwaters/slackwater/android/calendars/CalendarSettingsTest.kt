package io.openwaters.slackwater.android.calendars

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.alerts.AlertDestination
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class CalendarSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fridayHarbor = StationId("noaa/9449880")

    @Test fun freePreviewAndConfirmedRemoval() {
        val app = compose.activity.applicationContext
        val repository = CalendarRepository.get(app)
        assumeTrue(runBlocking { repository.subscriptions.first().none { it.stationId == fridayHarbor } })
        val access = ReminderAccessStore.get(app)
        val previous = runBlocking { access.current() }
        try {
            runBlocking { access.update(ReminderEntitlement.Free) }
            compose.onNodeWithText("Station calendars").performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Publish calendar") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Publish calendar").performClick()
            compose.onNodeWithText("Publishing a new station calendar requires Premium.").assertExists()
            assertFalse(runBlocking { repository.subscriptions.first().any { it.stationId == fridayHarbor } })
            grantCalendarPermissions(app)
            runBlocking { access.update(ReminderEntitlement.Purchased) }
            compose.waitForIdle()
            compose.onNodeWithText("Publish calendar").performClick()
            compose.waitUntil(90_000) {
                compose.onAllNodesWithText("Remove calendar").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasText("Remove calendar") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Remove calendar").performClick()
            val count = runBlocking { repository.eventCount(fridayHarbor) }
            assertTrue(count > 0)
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Remove Friday Harbor Tides?").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("remove $count event", substring = true).assertExists()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(count, runBlocking { repository.eventCount(fridayHarbor) })
            compose.onNodeWithText("Remove calendar").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Remove Friday Harbor Tides?").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Remove calendar") and
                hasAnyAncestor(hasAnyDescendant(hasText("Remove Friday Harbor Tides?"))) and hasClickAction()).performClick()
            compose.waitUntil(20_000) { runBlocking { repository.subscriptions.first().none { it.stationId == fridayHarbor } } }
        } finally {
            runBlocking {
                if (repository.subscriptions.first().any { it.stationId == fridayHarbor }) repository.unsubscribe(fridayHarbor)
                access.update(previous)
            }
        }
    }

    @Test fun providerCustomEventRoutesToStationAndEventTime() {
        val target = AlertDestination(fridayHarbor, Instant.parse("2026-11-06T15:00:00Z"))
        val app = compose.activity
        val calendarIntent = Intent(CalendarContract.ACTION_HANDLE_CUSTOM_EVENT)
            .setDataAndType(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 123), "vnd.android.cursor.item/event")
            .setPackage(app.packageName)
            .putExtra(CalendarContract.EXTRA_CUSTOM_APP_URI, AlertDestination.intent(app, target.station, target.event).dataString)
        assertEquals(MainActivity::class.java.name, calendarIntent.resolveActivity(app.packageManager)?.className)
        assertEquals(target, AlertDestination.read(calendarIntent))
    }
}
