package io.openwaters.slackwater.android.sharing

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.openStation
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PrintPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun openSheet() {
        compose.openStation("Friday Harbor", "San Juan Island")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("printable-tables-row").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("printable-tables-row").performScrollTo().performClick()
    }

    private fun ready() {
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Share PDF").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun openPreviewLocksWhenTheFreeDayExpires() {
        val store = ReminderAccessStore.get(compose.activity)
        val prior = runBlocking { store.current() }
        try {
            runBlocking { store.update(ReminderEntitlement.Purchased) }
            openSheet()
            ready()
            compose.onNodeWithText("Month").performClick()
            ready()
            runBlocking { store.update(ReminderEntitlement.FreeDay(Instant.now().plusMillis(350))) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Printable tables require Premium.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Share PDF").assertDoesNotExist()
            compose.onNodeWithText("Print").assertDoesNotExist()
        } finally { runBlocking { store.update(prior) } }
    }

    @Test fun switchingAndClosingReclaimsUnsharedPreviewFiles() {
        val app = compose.activity.applicationContext
        val store = ReminderAccessStore.get(app)
        val prior = runBlocking { store.current() }
        val root = File(app.cacheDir, "shared-tables")
        fun files() = root.walkTopDown().filter { it.isFile }.toSet()
        val initial = files()
        try {
            runBlocking { store.update(ReminderEntitlement.Purchased) }
            openSheet()
            ready()
            assertTrue((files() - initial).single().name.endsWith("Week.pdf"))
            compose.onNodeWithText("Month").performClick()
            ready()
            compose.waitUntil(10_000) { (files() - initial).singleOrNull()?.name?.endsWith("Month.pdf") == true }
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(10_000) { (files() - initial).isEmpty() }
        } finally { runBlocking { store.update(prior) } }
    }

    /** Host-driven on an emulator with the platform chooser and print spooler installed. */
    @Test fun nativeChooserOpensFromDisplayedMonth() = nativeSurface("Share PDF", "ChooserActivity")

    @Test fun nativeSpoolerOpensFromDisplayedMonth() = nativeSurface("Print", "printspooler")

    private fun nativeSurface(action: String, activity: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativePrint") == "true")
        val app = compose.activity.applicationContext
        val store = ReminderAccessStore.get(app)
        val prior = runBlocking { store.current() }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun resumed(text: String): Boolean = shell("dumpsys activity activities").lineSequence()
            .any { it.contains("ResumedActivity") && it.contains(text, ignoreCase = true) }
        try {
            runBlocking { store.update(ReminderEntitlement.Purchased) }
            openSheet()
            ready()
            compose.onNodeWithText("Month").performClick()
            ready()
            val pdf = File(app.cacheDir, "shared-tables").walkTopDown().filter { it.name.endsWith("Month.pdf") }.maxBy { it.lastModified() }
            pdf.copyTo(File(app.cacheDir, "native-print-sample.pdf"), overwrite = true)
            compose.onNodeWithText(action).performClick()
            compose.waitUntil(15_000) { resumed(activity) }
            instrumentation.uiAutomation.waitForIdle(250, 5_000)
            shell("input keyevent KEYCODE_BACK")
        } finally { runBlocking { store.update(prior) } }
    }
}
