package io.openwaters.slackwater.android.stations

import android.view.KeyEvent
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
}
