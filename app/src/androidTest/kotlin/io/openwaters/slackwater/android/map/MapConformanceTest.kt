package io.openwaters.slackwater.android.map

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.openStation
import org.junit.Rule
import org.junit.Test

class MapConformanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun stationHeaderOpensSelectedPinAndPreviewReturnsToDetail() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openStation("Friday Harbor", "San Juan Island")
            compose.onNodeWithText("Friday Harbor").performClick()
            val preview = hasContentDescription("Friday Harbor, San Juan Island", substring = true)
            compose.waitUntil(60_000) { compose.onAllNodes(preview).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("station-map").assertIsDisplayed()
            compose.onNodeWithTag("map-disclaimer").performClick()
            compose.onNodeWithText("Tide heights").assertIsDisplayed()
            compose.onNode(hasText("Close") and hasAnyAncestor(isDialog())).performClick()
            compose.onNode(preview).performClick()
            compose.onNodeWithText("Friday Harbor").assertIsDisplayed()
        }
    }
}
