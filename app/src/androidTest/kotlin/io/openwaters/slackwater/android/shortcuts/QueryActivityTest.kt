package io.openwaters.slackwater.android.shortcuts

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.junit.Rule
import org.junit.Test

class QueryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun datedNamedShortcutShowsBothFridayHarborHighTides() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val day = LocalDate.of(2026, 7, 16)
        val request = ShortcutRequest(ShortcutKind.HIGH, "Friday Harbor", day)
        val intent = ShortcutPublisher.intent(context, request)
        ActivityScenario.launch<QueryActivity>(intent).use {
            val locale = context.resources.configuration.locales[0]
            val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
            val event = hasText(date, substring = true) and hasText(" · ", substring = true)
            compose.waitUntil(20_000) { compose.onAllNodes(event).fetchSemanticsNodes().size == 2 }
            compose.onNodeWithText(context.getString(R.string.query_high_day)).assertIsDisplayed()
            compose.onAllNodes(hasText("Friday Harbor") and !hasSetTextAction()).assertCountEquals(1)
            val rows = compose.onAllNodes(event).fetchSemanticsNodes()
            assertEquals(listOf(0, 1), rows.map { it.config[SemanticsProperties.CollectionItemInfo].rowIndex })
            val table = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo))
            assertEquals(2, table.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount)
        }
    }
}
