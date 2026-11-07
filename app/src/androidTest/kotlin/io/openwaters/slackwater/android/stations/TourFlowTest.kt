package io.openwaters.slackwater.android.stations

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.tour.TOUR_FALLBACK_STATION
import io.openwaters.slackwater.android.core.tour.tourStation
import io.openwaters.slackwater.android.data.ListPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The first-run tour (list §11), from a launch with the tour unseen. The tour's own station depends on the fix the
 * device holds, which an earlier test may have left anywhere, so most tests start from a link to Seattle, which wins
 * over the station the tour would have opened.
 */
class TourFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ListPreferencesStore.get(context)
    private val model = StationListModel.get(context)

    @Before fun firstRun() { runBlocking {
        store.clearTourSeen()
        // The list reads the flag through the model, which may still hold the last test's "seen".
        model.preferences.first { it?.tourSeen == false }
        // The model arms once per process, which an earlier test's launch has already used.
        model.tour.replay()
    } }

    @After fun seen() { runBlocking {
        model.tour.standDown()
        store.markTourSeen()
    } }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val seattle = Intent(Intent.ACTION_VIEW, Uri.parse("https://slackwater.xyz/tides/seattle"), context, MainActivity::class.java)

    private fun tourStationName(): String {
        val catalog = runBlocking { model.catalog.first { it != null }!! }
        return catalog.byId.getValue(tourStation(model.location.cachedFix()?.coordinate, catalog.stations)).name
    }

    private fun awaitMark() = try {
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("tour-mark").fetchSemanticsNodes().isNotEmpty() }
    } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("No tour mark; the tour is ${model.tour.state.value}", timeout)
    }

    private fun awaitSeen() {
        runBlocking { withTimeout(10_000) { store.preferences.first { it.getOrNull()?.tourSeen == true } } }
    }

    private fun storedSeen() = runBlocking { store.preferences.first().getOrNull()?.tourSeen }

    @Test fun theFallbackStationIsInTheCatalog() {
        val catalog = runBlocking { model.catalog.first { it != null }!! }
        assertEquals("Friday Harbor", catalog.byId.getValue(TOUR_FALLBACK_STATION).name)
    }

    @Test fun aFirstRunOpensTheTourStation() {
        val expected = tourStationName()
        ActivityScenario.launch(MainActivity::class.java).use {
            // A wide window shows the name in the list beside the page, so any match will do.
            compose.waitUntil(60_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(expected)[0].assertIsDisplayed()
        }
    }

    @Test fun theTourWalksEveryMarkAndStaysSeen() {
        ActivityScenario.launch<MainActivity>(seattle).use {
            awaitMark()
            compose.onNodeWithText("Seattle").assertIsDisplayed()
            compose.onNodeWithText(string(R.string.tour_read_title)).assertIsDisplayed()
            // The sky steps come and go with the night ahead, so walk to the last mark whatever lies between.
            repeat(4) {
                if (compose.onAllNodesWithText(string(R.string.tour_done)).fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithTag("tour-next").performClick()
                    compose.waitForIdle()
                }
            }
            compose.onNodeWithText(string(R.string.tour_star_title)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.tour_done)).performClick()
            compose.onNodeWithTag("tour-mark").assertDoesNotExist()
            awaitSeen()
        }
    }

    @Test fun skipEndsTheTourFromItsFirstMark() {
        ActivityScenario.launch<MainActivity>(seattle).use {
            awaitMark()
            compose.onNodeWithContentDescription(string(R.string.tour_skip)).performClick()
            compose.onNodeWithTag("tour-mark").assertDoesNotExist()
            awaitSeen()
        }
    }

    @Test fun aRotationOrAClosedActivityKeepsTheTour() {
        val scenario = ActivityScenario.launch<MainActivity>(seattle)
        try {
            awaitMark()
            compose.onNodeWithTag("tour-next").performClick()
            val step = model.tour.state.value.step
            scenario.recreate()
            awaitMark()
            assertEquals(step, model.tour.state.value.step)
        } finally {
            scenario.close()
        }
        // Closing the app is not leaving the station, so the tour is still to be seen.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(false, storedSeen())
    }

    @Test fun leavingTheStationEndsTheTour() {
        ActivityScenario.launch<MainActivity>(seattle).use {
            awaitMark()
            // A nearby station's card, which opens that station in this one's place.
            val nearby = hasClickAction() and hasContentDescription(" nm", substring = true)
            compose.waitUntil(30_000) { compose.onAllNodes(nearby).fetchSemanticsNodes().isNotEmpty() }
            // Its click action, since a touch could land on the tour's card over it.
            compose.onAllNodes(nearby)[0].performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(30_000) { model.tour.state.value.station == null }
            awaitSeen()
            assertEquals(null, model.tour.state.value.step)
        }
    }

    @Test fun aFirstRunLinkWinsOverTheTourStation() {
        ActivityScenario.launch<MainActivity>(seattle).use {
            awaitMark()
            compose.onNodeWithText("Seattle").assertIsDisplayed()
            assertEquals("noaa/9447130", model.tour.state.value.station?.value)
        }
    }
}
