package io.openwaters.slackwater.android.map

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import io.openwaters.slackwater.android.data.SetFormat
import io.openwaters.slackwater.android.stations.StationListModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ReadingSettingsScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun footerOpensSettingsAndRetainsTheChosenFormatAfterRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReadingPreferencesStore.get(context)
        val original = runBlocking { store.preferences.first().getOrThrow().setFormat }
        val next = if (original == SetFormat.COMPASS) SetFormat.DEGREES else SetFormat.COMPASS
        val label = context.getString(if (next == SetFormat.COMPASS) R.string.settings_compass else R.string.settings_degrees)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                compose.waitUntil(60_000) { StationListModel.get(context).state.value != null }
                val title = context.getString(R.string.settings_title)
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
                compose.onNodeWithText(title).performClick()
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodes(hasText(label) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
                activity.recreate()
                compose.waitUntil(10_000) { compose.onAllNodes(hasText(label) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            val restore = CoroutineScope(Dispatchers.IO).async { store.setSetFormat(original) }
            compose.waitUntil(10_000) { restore.isCompleted }
            runBlocking { restore.await() }
        }
    }
}
