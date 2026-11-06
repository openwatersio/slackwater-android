package io.openwaters.slackwater.android.map

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.openStation
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.StationSeries
import io.openwaters.slackwater.android.stations.StationListModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MapConformanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun stationHeaderOpensSelectedPinAndPreviewReturnsToDetail() {
        val model = StationListModel.get(InstrumentationRegistry.getInstrumentation().targetContext)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openStation("Friday Harbor", "San Juan Island")
            val previous = model.state.value?.filter
            try {
                runBlocking { model.listPreferences.setSeriesFilter(StationSeries.Current) }
                compose.waitUntil { model.state.value?.filter == StationSeries.Current }
                compose.onNodeWithText("Friday Harbor").performClick()
                val preview = hasContentDescription("Friday Harbor, San Juan Island", substring = true)
                compose.waitUntil(60_000) { compose.onAllNodes(preview).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(StationSeries.Current, model.state.value?.filter)
                compose.onNodeWithTag("station-map").assertIsDisplayed()
                compose.onNodeWithText("All").performClick()
                compose.waitUntil { model.state.value?.filter == null }
                compose.onNode(preview).assertExists()
                compose.onNodeWithTag("map-disclaimer").performClick()
                compose.onNodeWithText("Tide heights").assertIsDisplayed()
                compose.onNode(hasText("Close") and hasAnyAncestor(isDialog())).performClick()
                compose.onNode(preview).performClick()
                compose.onNodeWithText("Friday Harbor").assertIsDisplayed()
            } finally {
                runBlocking { model.listPreferences.setSeriesFilter(previous) }
            }
        }
    }
}
