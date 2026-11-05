package io.openwaters.slackwater.android
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import io.openwaters.slackwater.android.core.FixtureSmoke
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import android.content.pm.PackageManager
class LaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun engineAndAstronomyMatchFixtures() { FixtureSmoke.verify() }
    @Test fun readsBundledCredits() {
        compose.onNodeWithText("Data and licenses").performClick()
        compose.onNodeWithText("Data credits").assertIsDisplayed()
        compose.onNodeWithText("Norwegian stations:", substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Data credits").assertDoesNotExist()
    }
    @Test fun launchesWithoutNetwork() {
        val permissions = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        compose.onNodeWithText("Slackwater").assertIsDisplayed()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Friday Harbor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Friday Harbor").assertIsDisplayed()
    }
}
