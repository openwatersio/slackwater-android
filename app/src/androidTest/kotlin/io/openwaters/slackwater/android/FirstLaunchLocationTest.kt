package io.openwaters.slackwater.android

import android.Manifest
import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.openwaters.slackwater.android.core.tour.TOUR_FALLBACK_STATION
import io.openwaters.slackwater.android.core.tour.TourStep
import io.openwaters.slackwater.android.data.ListPreferencesStore
import io.openwaters.slackwater.android.stations.StationListModel
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The host runs the two launches in separate processes, starting with freshly cleared debug-app data. */
class FirstLaunchLocationTest {
    @Test fun realPermissionPrompt() {
        val phase = InstrumentationRegistry.getArguments().getString("locationPromptPhase")
        assumeTrue("Run scripts/test-location-prompt.sh on a disposable emulator", phase != null)
        require(phase == "first" || phase == "relaunch")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = ListPreferencesStore.get(context).preferences
        val device = UiDevice.getInstance(instrumentation)
        val permissionController = "com\\.(?:google\\.)?android\\.(?:packageinstaller|permissioncontroller):id/"
        val deny = By.res(Pattern.compile(permissionController + "permission_deny_button"))
        val anyDeny = By.res(Pattern.compile(permissionController + "permission_deny(?:_and_dont_ask_again)?_button"))
        val first = phase == "first"
        val stored = runBlocking { prefs.first().getOrThrow() }
        assertEquals(!first, stored.locationAsked)
        assertFalse(stored.tourSeen)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
        }

        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (first) {
            device.awaitControl(deny, "first Android permission prompt")
            runBlocking {
                withTimeout(60_000) {
                    StationListModel.get(context).tour.state.first { it.station == TOUR_FALLBACK_STATION && it.step == TourStep.READ }
                }
            }
            requireNotNull(device.findObject(deny)).click()
            assertTrue("Android did not dismiss the location dialog after denial", device.wait(Until.gone(deny), 10_000))
        }
        val mark = By.text(context.getString(R.string.tour_read_title))
        device.awaitControl(mark, "tour reading step")
        device.awaitControl(By.text("Friday Harbor"), "Friday Harbor station heading")
        if (!first) assertFalse("Automatic location prompt on relaunch", device.wait(Until.hasObject(anyDeny), 2_000))
        assertTrue(runBlocking { prefs.first().getOrThrow().locationAsked })

        if (!first) {
            // Android can still ask after one denial. Only the user's explicit request should show it now.
            device.awaitControl(By.desc(context.getString(R.string.tour_skip)), "Skip tour").click()
            if (!device.wait(Until.gone(mark), 10_000)) device.missing("tour dismissal")
            val back = By.pkg(context.packageName).desc(context.getString(R.string.station_back))
            device.awaitControl(back, "station Back button after tour dismissal").click()
            if (!device.wait(Until.gone(back), 10_000)) device.missing("station page dismissal")
            val request = By.text(context.getString(R.string.list_find_tides_near_me))
            device.awaitControl(request, "explicit location request on the station list")
            assertFalse("Automatic location prompt on return to the list", device.wait(Until.hasObject(anyDeny), 2_000))
            device.awaitControl(request, "explicit location request").click()
            device.awaitControl(anyDeny, "permission prompt after explicit location request").click()
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nVerified location prompt: $phase\n")
        })
    }

    private fun UiDevice.awaitControl(selector: BySelector, name: String) =
        wait(Until.findObject(selector), 60_000) ?: missing(name)

    private fun UiDevice.missing(name: String): Nothing {
        val hierarchy = ByteArrayOutputStream()
        dumpWindowHierarchy(hierarchy)
        throw AssertionError("Timed out waiting for $name. Visible UI:\n" + hierarchy.toString("UTF-8"))
    }

}
