package io.openwaters.slackwater.android

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput

/**
 * Opens a station's page from the list through search, as a person would. [name] and [region] begin the card's
 * spoken description, so a station whose region merely mentions the name is never picked instead.
 */
fun ComposeTestRule.openStation(name: String, region: String) {
    waitUntil(30_000) { onAllNodesWithText("Search").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    onNodeWithText("Search").performClick()
    onNode(hasSetTextAction()).performTextInput(name)
    val card = hasContentDescription("$name, $region", substring = true)
    // Search answers once the catalog has loaded, which a cold emulator can take most of a minute to do.
    waitUntil(60_000) { onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
    onAllNodes(card).onFirst().performClick()
}
