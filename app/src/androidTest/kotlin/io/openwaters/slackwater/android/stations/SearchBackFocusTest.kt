package io.openwaters.slackwater.android.stations

import android.view.KeyEvent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import io.openwaters.slackwater.android.data.BundledStationCatalog
import io.openwaters.slackwater.android.data.UserStateStore
import java.io.File
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.openwaters.slackwater.android.R
import org.junit.Rule
import org.junit.Test

class SearchBackFocusTest {
    @get:Rule val files = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    @Test fun nativeBackReturnsKeyboardFocusToSearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = instrumentation.targetContext.getString(R.string.list_search)
        val button = compose.onNodeWithText(label)
        compose.setContent { StationsHome(onCredits = {}) }
        val model = StationListModel.get(instrumentation.targetContext)
        compose.waitUntil(60_000) { model.state.value != null }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        closeSoftKeyboard()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        UiDevice.getInstance(instrumentation).pressBack()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        button.assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test fun expandedSearchBackPreservesTheLinkedStationUntilTheNextBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val places = UserStateStore.open(File(files.root, "places.preferences_pb"), BundledStationCatalog(context.assets), scope)
        val model = StationListModel(context, scope = scope, places = places)
        val parent = "0.15 nm WSW of Pier No.2"
        val child = "Newport News"
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        val childPage = heading and hasText(child)
        val parentPage = heading and hasText(parent)
        val search = compose.onNodeWithText(context.getString(R.string.list_search))
        val device = UiDevice.getInstance(instrumentation)
        try {
            compose.setContent {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = with(LocalDensity.current) { minOf(maxWidth.toPx() / 720f, maxHeight.toPx() / 500f) }
                    CompositionLocalProvider(LocalDensity provides Density(density)) { StationsHome(onCredits = {}, model = model) }
                }
            }
            compose.waitUntil(60_000) { model.state.value != null && compose.onAllNodesWithTag("detailTimeline").fetchSemanticsNodes().isNotEmpty() }
            search.assertIsDisplayed().performClick()
            compose.onNode(hasSetTextAction()).performTextInput(parent)
            val result = hasContentDescription(parent, substring = true)
            compose.waitUntil(30_000) { compose.onAllNodes(result).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(result).performClick()

            val tideLink = context.getString(R.string.detail_related_tides, child)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(tideLink).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(tideLink).performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(childPage).fetchSemanticsNodes().isNotEmpty() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            search.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            compose.onNode(hasSetTextAction()).assertIsFocused()
            closeSoftKeyboard()
            device.pressBack()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            search.assertIsFocused()
            compose.onNode(childPage).assertIsDisplayed()
            device.pressBack()
            compose.waitUntil("Back returns to the current station", 30_000) {
                compose.onAllNodes(parentPage).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(childPage).assertDoesNotExist()
        } finally {
            runBlocking { job.cancelAndJoin() }
        }
    }
}
