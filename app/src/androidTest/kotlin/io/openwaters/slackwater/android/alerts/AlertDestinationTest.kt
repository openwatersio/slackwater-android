package io.openwaters.slackwater.android.alerts

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AlertDestinationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun openFridayHarborFromSearch() {
        compose.onNodeWithText("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Friday Harbor")
        val friday = hasContentDescription("Friday Harbor, San Juan Island", substring = true)
        compose.waitUntil(30_000) { compose.onAllNodes(friday).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(friday).performClick()
    }

    @Test fun notificationClosesTheUnderlyingDatePickerWhenReturningToTheStation() {
        openFridayHarborFromSearch()
        val week = hasTestTag("schedule-week")
        compose.waitUntil(45_000) { compose.onAllNodes(week).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(week).performScrollTo().performClick()
        compose.onNodeWithText("Choose a date").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(45_000) { compose.onAllNodesWithText("Choose a date").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Switch to calendar input mode").assertExists()
        val activity = compose.activity
        val originalIntent = activity.intent
        val intent = AlertDestination.intent(activity, StationId("noaa/9447130"), Instant.parse("2026-11-06T16:00:00Z"))
        try {
            compose.runOnUiThread {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, intent)
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Seattle").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Seattle").assertIsDisplayed()
            compose.onNodeWithText("Choose a date").assertDoesNotExist()
            compose.waitUntil(45_000) { compose.onAllNodes(week).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(week).performScrollTo().performClick()
            compose.onNodeWithText("Choose a date").assertIsDisplayed()
            compose.runOnUiThread {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, intent)
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Choose a date").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Seattle").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to stations").performClick()
            compose.waitUntil(45_000) { compose.onAllNodes(week).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Friday Harbor").assertExists()
            compose.onNodeWithText("Choose a date").assertDoesNotExist()
            compose.onNode(week).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Switch to text input mode").assertExists()
        } finally {
            compose.runOnUiThread { activity.intent = originalIntent }
        }
    }

    @Test fun notificationDismissesAnOpenAlertEditor() {
        openFridayHarborFromSearch()
        compose.waitUntil(45_000) { compose.onAllNodesWithText("Set an alert").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set an alert").performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
        val activity = compose.activity
        val originalIntent = activity.intent
        try {
            val intent = AlertDestination.intent(activity, StationId("noaa/9447130"), Instant.parse("2026-11-06T16:00:00Z"))
            compose.runOnUiThread {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, intent)
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Seattle").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Back to stations").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Friday Harbor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(isDialog()).assertDoesNotExist()
        } finally {
            compose.runOnUiThread { activity.intent = originalIntent }
        }
    }

    @Test fun warmNotificationTapOpensTheActualStationAtEventTime() {
        val target = AlertDestination(StationId("noaa/9447130"), Instant.parse("2026-11-06T16:00:00Z"))
        val intent = AlertDestination.intent(compose.activity, target.station, target.event)
        assertEquals(target, AlertDestination.read(intent))
        val activity = compose.activity
        val launch = activity.intent
        compose.runOnUiThread {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, intent)
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Seattle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Seattle").assertIsDisplayed()
        val chart = hasTestTag("detailTimeline")
        compose.waitUntil(45_000) { compose.onAllNodes(chart).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(chart).assertExists()
        compose.onNodeWithContentDescription("Back to stations").performClick()
        // Back on the station list.
        compose.onNodeWithText("Search").assertExists()
        compose.runOnUiThread { activity.intent = launch }
    }
}
