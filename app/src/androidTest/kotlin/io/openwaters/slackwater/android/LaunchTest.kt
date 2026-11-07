package io.openwaters.slackwater.android
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.FixtureSmoke
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LaunchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    // SlackwaterTestRunner has already recorded the location ask, so no system dialog covers the list.
    @Before fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() = scenario.close()

    @Test fun engineAndAstronomyMatchFixtures() { FixtureSmoke.verify() }

    @Test fun readsBundledCredits() {
        // The catalog loads once per process; on a cold emulator that can take most of a minute.
        val nearMe = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.list_near_me)
        compose.waitUntil(60_000) { compose.onAllNodesWithText(nearMe, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Chesapeake Bay", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Data and licenses"))
        compose.onNodeWithText("Data and licenses").performClick()
        compose.onNodeWithText("Data credits").assertIsDisplayed()
        val credits = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
        credits.performScrollToNode(hasText("Norwegian stations:", substring = true))
        compose.onNodeWithText("Norwegian stations:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Data credits").assertDoesNotExist()
    }

    @Test fun launchesAndReadsTheBundledStations() {
        // Near Me cards show live readings computed from the bundled database, wherever the list is anchored.
        compose.waitUntil(60_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            listOf(R.string.card_rising, R.string.card_falling, R.string.card_flooding, R.string.card_ebbing, R.string.card_slack)
                .map(context::getString).any { word ->
                // Each card is one element whose spoken description carries its reading.
                compose.onAllNodes(hasContentDescription(word, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
