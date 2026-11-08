package io.openwaters.slackwater.android.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SlackWindowPreviewAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exampleAnnouncesOffAndUpdatedDurationAtNarrowWidth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val threshold = mutableDoubleStateOf(0.0)
        compose.setContent { Box(Modifier.width(120.dp)) { SlackWindowPreview(threshold.doubleValue) } }
        val preview = compose.onNodeWithContentDescription(context.getString(R.string.settings_example_window))
        fun value() = preview.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val off = context.getString(R.string.reminder_status_off)
        assertEquals(off, value())
        compose.runOnIdle { threshold.doubleValue = 0.5 }
        val duration = value()
        assertNotEquals(off, duration)
        compose.runOnIdle { threshold.doubleValue = 1.0 }
        assertNotEquals(duration, value())
    }
}
