package io.openwaters.slackwater.android.stations

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.SavedPlaces
import io.openwaters.slackwater.android.data.UserStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class StationListTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val places = UserStateStore.get(context)
    private val fridayHarbor = StationId("noaa/9449880")
    private val withdrawn = StationId("chs-aulatsivik-point")
    private lateinit var saved: SavedPlaces

    // These tests write to the device's own saved places, so they put back what was there.
    @Before fun seed() = runBlocking {
        saved = places.places.first().getOrThrow()
        for (id in saved.favorites) places.forgetFavorite(id)
        compose.waitUntil(60_000) { StationListModel.get(context).state.value?.favorites?.isEmpty() == true }
        places.setFavorite(fridayHarbor, true)
        places.setFavorite(withdrawn, true)
        compose.waitUntil(60_000) { StationListModel.get(context).state.value?.sections?.favorites == listOf(fridayHarbor, withdrawn) }
    }

    @After fun restore() = runBlocking {
        val now = places.places.first().getOrThrow()
        for (id in now.favorites) places.forgetFavorite(id)
        for (id in now.recents) places.removeRecent(id)
        for (id in saved.favorites) places.setFavorite(id, true)
        for (id in saved.recents.asReversed()) places.recordVisit(id)
    }

    private fun scrollTo(text: String) {
        compose.waitUntil(60_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test fun favoritesShowLiveAndRemovedStationsInStarOrder() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val card = hasContentDescription("Friday Harbor, San Juan Island", substring = true)
            compose.waitUntil(60_000) { compose.onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            // Star order: Friday Harbor was starred first, so it is listed first. Screen positions vary with the
            // device's height, so the order is read from what the list draws.
            compose.waitUntil(60_000) { StationListModel.get(context).state.value?.sections?.favorites == listOf(fridayHarbor, withdrawn) }
            // The tombstone's name, with copy that says only that CHS no longer publishes it.
            scrollTo("Arctic Coast — no longer published.")
            compose.onNodeWithText("Aulatsivik Point").assertExists()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Remove"))
            compose.onNodeWithText("Remove").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Aulatsivik Point").fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf(fridayHarbor), runBlocking { places.places.first().getOrThrow().favorites })
        }
    }

    @Test fun unstarringFromTheStationPageMovesItToRecents() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val card = hasContentDescription("Friday Harbor, San Juan Island", substring = true)
            compose.waitUntil(60_000) { compose.onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(card).onFirst().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remove from favorites").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Remove from favorites").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Add to favorites").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) {
                runBlocking {
                    val stored = places.places.first().getOrThrow()
                    stored.favorites == listOf(withdrawn) && stored.recents.firstOrNull() == fridayHarbor
                }
            }
            val stored = runBlocking { places.places.first().getOrThrow() }
            assertEquals(listOf(withdrawn), stored.favorites)
            assertEquals(fridayHarbor, stored.recents.first())
        }
    }
}
