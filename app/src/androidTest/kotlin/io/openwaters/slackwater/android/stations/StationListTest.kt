package io.openwaters.slackwater.android.stations

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            val favorites = runBlocking {
                withTimeout(10_000) {
                    places.places.first { withdrawn !in it.getOrThrow().favorites }.getOrThrow().favorites
                }
            }
            assertEquals(listOf(fridayHarbor), favorites)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Aulatsivik Point").fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun keyboardCanRemoveOneRecentAndKeepTheOthers() {
        val boston = StationId("noaa/8443970")
        val seattle = StationId("noaa/9447130")
        val model = StationListModel.get(context)
        runBlocking {
            places.recordVisit(seattle)
            places.recordVisit(boston)
            places.recordVisit(StationId("noaa/8575512"))
        }
        compose.waitUntil(60_000) { model.state.value?.sections?.recents?.containsAll(listOf(boston, seattle)) == true }
        val before = runBlocking { places.places.first().getOrThrow() }
        ActivityScenario.launch(MainActivity::class.java).use {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bostonCard = hasContentDescription("Boston,", substring = true)
            compose.waitUntil(60_000) {
                compose.onAllNodes(hasContentDescription("Friday Harbor,", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            for (step in 1..60) {
                if (compose.onAllNodes(bostonCard and isFocused()).fetchSemanticsNodes().isNotEmpty()) break
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                compose.waitForIdle()
            }
            val focusedCard = compose.onNode(bostonCard and isFocused()).fetchSemanticsNode()
            val remove = context.getString(io.openwaters.slackwater.android.R.string.action_remove)
            assertTrue(focusedCard.config[SemanticsActions.CustomActions].any { it.label == remove })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            compose.onNode(hasText("$remove · Boston") and isFocused()).assertExists()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(10_000) { model.state.value?.sections?.recents?.contains(boston) == false }
            val after = runBlocking { places.places.first().getOrThrow() }
            assertEquals(before.recents - boston, after.recents)
            assertEquals(before.favorites, after.favorites)
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
