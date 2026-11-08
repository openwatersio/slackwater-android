package io.openwaters.slackwater.android.shortcuts

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QueryLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun datedQueryControlsAndEventRowsFitAndRemainReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ShortcutPublisher.intent(context, ShortcutRequest(ShortcutKind.HIGH, "Friday Harbor", LocalDate.of(2026, 7, 16)))
        ActivityScenario.launch<QueryActivity>(intent).use { scenario ->
            scenario.onActivity {
                val config = it.resources.configuration
                android.util.Log.i("QueryLayout", "${config.screenWidthDp}x${config.screenHeightDp} dp, font=${config.fontScale}, locale=${config.locales[0]}")
            }
            val station = hasText("Friday Harbor") and !hasSetTextAction()
            compose.waitUntil(30_000) { compose.onAllNodes(station).fetchSemanticsNodes().size == 1 }
            val submit = compose.onNodeWithText(context.getString(R.string.query_show))
            submit.performScrollTo().assertIsDisplayed().assertHasClickAction()

            val events = compose.onAllNodes(hasText(" · ", substring = true), useUnmergedTree = true)
            val count = events.fetchSemanticsNodes().size
            assertEquals("Dated high tides at Friday Harbor", 2, count)
            for (index in 0 until count) {
                val row = events[index].performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                row.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Missing text layout", layouts.isNotEmpty())
                for (layout in layouts) {
                    val bounds = row.fetchSemanticsNode().size
                    val last = layout.lineCount - 1
                    val text = layout.layoutInput.text.text
                    assertTrue("Incomplete text: $text", layout.getLineEnd(last) == text.length)
                    for (line in 0..last) {
                        assertFalse("Ellipsized: $text", layout.isLineEllipsized(line))
                        assertTrue("Clipped line: $text (line end ${layout.getLineRight(line)}, bottom ${layout.getLineBottom(line)}, node $bounds)",
                            layout.getLineRight(line) <= bounds.width + 1 && layout.getLineBottom(line) <= bounds.height + 1)
                    }
                }
            }
        }
    }
}
