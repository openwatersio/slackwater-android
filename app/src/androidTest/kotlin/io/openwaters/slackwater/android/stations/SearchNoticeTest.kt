package io.openwaters.slackwater.android.stations

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import java.time.Instant
import org.junit.Rule
import org.junit.Test

class SearchNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noticesAndCardsChangeTogetherAfterRapidEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = StationListModel.get(context)
        val truncated = context.getString(R.string.search_truncated)
        val noMatches = context.getString(R.string.search_no_matches)
        val card = hasClickAction() and hasContentDescription(",", substring = true)
        val now = Instant.parse("2026-11-06T16:00:00Z")
        compose.setContent { SearchOverlay(model, now, {}, {}) }
        compose.waitUntil(30_000) { compose.onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(truncated).assertExists()

        val firstCard = compose.onAllNodes(card).onFirst().fetchSemanticsNode()
        val station = firstCard.config[SemanticsProperties.ContentDescription].first().substringBefore(',')
        val stationCard = hasContentDescription(station + ",", substring = true)
        val input = compose.onNode(hasSetTextAction())
        compose.mainClock.autoAdvance = false
        input.performTextReplacement(station)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(truncated).assertExists()
        compose.onAllNodes(stationCard).onFirst().assertExists()

        input.performTextReplacement("zzzz-no-such-station")
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(truncated).assertExists()
        compose.onAllNodes(stationCard).onFirst().assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { compose.onAllNodes(card).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(noMatches).assertExists()
        compose.onNodeWithText(truncated).assertDoesNotExist()

        compose.mainClock.autoAdvance = false
        input.performTextReplacement(station)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(noMatches).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) {
            compose.onAllNodes(stationCard).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(noMatches).assertDoesNotExist()
    }
}
