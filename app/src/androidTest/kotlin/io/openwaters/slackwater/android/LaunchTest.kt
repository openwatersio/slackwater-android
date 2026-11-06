package io.openwaters.slackwater.android
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
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
import io.openwaters.slackwater.android.data.ListPreferencesStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LaunchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    // The list asks for location once on a first launch; a system dialog over the screen would hide it from the test.
    @Before fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { ListPreferencesStore.get(context).markLocationAsked() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() = scenario.close()

    @Test fun engineAndAstronomyMatchFixtures() { FixtureSmoke.verify() }

    @Test fun readsBundledCredits() {
        // The catalog loads once per process; on a cold emulator that can take most of a minute.
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Near Me", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Chesapeake Bay", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Data and licenses"))
        compose.onNodeWithText("Data and licenses").performClick()
        compose.onNodeWithText("Data credits").assertIsDisplayed()
        compose.onNodeWithText("Norwegian stations:", substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Data credits").assertDoesNotExist()
    }

    @Test fun launchesWithoutNetworkAndReadsTheBundledStations() {
        lateinit var permissions: Array<out String>
        scenario.onActivity {
            permissions = it.packageManager.getPackageInfo(it.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        }
        assertFalse(permissions.contains("android.permission.INTERNET"))
        // Near Me cards show live readings computed from the bundled database, wherever the list is anchored.
        compose.waitUntil(60_000) {
            listOf("Rising", "Falling", "Flooding", "Ebbing", "Slack").any { word ->
                // Each card is one element whose spoken description carries its reading.
                compose.onAllNodes(hasContentDescription(word, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
