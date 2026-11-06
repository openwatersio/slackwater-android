package io.openwaters.slackwater.android.alerts

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AlertDestinationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
        compose.onNodeWithText("08:00", substring = true).assertExists()
        compose.onNodeWithText("Friday Harbor").assertDoesNotExist()
        compose.onNodeWithText("Back to stations").performClick()
        compose.onNodeWithText("Friday Harbor").assertExists()
        compose.runOnUiThread { activity.intent = launch }
    }
}
