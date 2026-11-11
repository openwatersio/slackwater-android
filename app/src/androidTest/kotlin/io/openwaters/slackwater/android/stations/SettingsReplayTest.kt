package io.openwaters.slackwater.android.stations

import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.tour.TourStep
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsReplayTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = StationListModel.get(context)
    private val reading = ReadingPreferencesStore.get(context)
    private var previousSky = true
    private var previousRecents = emptyList<StationId>()
    private var previousSeen = true

    @Before fun prepare() = runBlocking {
        previousSky = reading.preferences.first().getOrThrow().showSky
        previousSeen = model.listPreferences.preferences.first().getOrThrow().tourSeen
        previousRecents = model.places.places.first().getOrThrow().recents
        model.listPreferences.markTourSeen()
        model.preferences.first { it?.tourSeen == true }
        model.tour.standDown()
        Unit
    }

    @After fun restore() = runBlocking {
        model.tour.standDown()
        reading.setShowSky(previousSky)
        if (!previousSeen) model.listPreferences.clearTourSeen()
        val current = model.places.places.first().getOrThrow().recents
        for (station in current) model.places.removeRecent(station)
        for (station in previousRecents.asReversed()) model.places.recordVisit(station)
    }

    private fun replay(coordinate: Coordinate?) {
        val settings = context.getString(R.string.settings_title)
        compose.setContent {
            StationsHome(onCredits = {}, cachedTourCoordinate = { coordinate }) { openSettings ->
                TextButton(onClick = openSettings) { Text(settings) }
            }
        }
        val list = compose.onNode(hasScrollAction())
        compose.waitUntil("Station list has a footer", 60_000) {
            compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().any { node ->
                SemanticsProperties.IndexForKey in node.config && node.config[SemanticsProperties.IndexForKey]("footer") >= 0
            }
        }
        compose.waitUntil("Settings footer is available", 30_000) {
            // Card heights can change while predictions load, moving the footer out of composition.
            list.performScrollToKey("footer")
            val footer = compose.onAllNodes(hasText(settings) and hasClickAction()).fetchSemanticsNodes().singleOrNull()
            compose.runOnIdle {
                footer != null && footer.layoutInfo.isAttached &&
                    footer.config[SemanticsActions.OnClick].action?.invoke() == true
            }
        }
        compose.onNodeWithText(context.getString(R.string.tour_replay_title)).performScrollTo().performClick()
    }

    @Test fun seenTourReplaysAtTheCachedFixWithTheSkyRestored() {
        runBlocking { reading.setShowSky(false); model.readingPreferences.first { !it.showSky } }
        replay(Coordinate(47.6026, -122.3393))
        compose.waitUntil(60_000) { model.tour.state.value.step == TourStep.READ }
        assertEquals("noaa/9447130", model.tour.state.value.station?.value)
        compose.onNodeWithText(context.getString(R.string.tour_read_title)).assertIsDisplayed()
        assertEquals(true, model.readingPreferences.value.showSky)
        assertEquals(true, model.preferences.value?.tourSeen)
        if (!context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) {
            compose.onNodeWithTag("tour-next").performClick()
            compose.waitUntil(10_000) { model.tour.state.value.step == TourStep.STARS }
        }
        compose.onNodeWithTag("tour-skip").performClick()
        assertEquals(true, model.preferences.value?.tourSeen)
    }

    @Test fun replayWithoutAFixOpensFridayHarbor() {
        replay(null)
        compose.waitUntil(60_000) { model.tour.state.value.step == TourStep.READ }
        compose.onNodeWithText("Friday Harbor").assertIsDisplayed()
        assertEquals("noaa/9449880", model.tour.state.value.station?.value)
    }

    @Test fun leavingCanadianReplayWaitingPageReturnsToUsableList() {
        replay(Coordinate(49.1659, -123.9401))
        val waiting = context.getString(R.string.detail_no_data)
        compose.waitUntil(60_000) { compose.onAllNodesWithText(waiting).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nanaimo Harbour").assertIsDisplayed()
        compose.onNodeWithTag("tour-mark").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.station_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.list_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Seattle")
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription("Seattle,", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(true, model.preferences.value?.tourSeen)
    }
}
