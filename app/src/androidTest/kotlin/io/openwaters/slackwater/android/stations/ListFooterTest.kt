package io.openwaters.slackwater.android.stations

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.BuildConfig
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.SlackwaterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** List §8: the footer is one card of rows, each at least 48 dp tall, then the wordmark. */
class ListFooterTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theFooterIsACardOfRowsThenTheWordmark() {
        var downloads = 0
        var settings = 0
        var support = 0
        compose.setContent {
            SlackwaterTheme {
                LazyColumn { listFooter(onDownloads = { downloads++ }, onSettings = { settings++ }, onSupport = { support++ }) }
            }
        }
        val downloadsRow = compose.onNodeWithTag("footerDownloads")
        val settingsRow = compose.onNodeWithTag("footerSettings")
        downloadsRow.assertHeightIsAtLeast(48.dp).assertHasClickAction().performClick()
        settingsRow.assertHeightIsAtLeast(48.dp).assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(1, downloads)
            assertEquals(1, settings)
        }
        assert(downloadsRow.getBoundsInRoot().top < settingsRow.getBoundsInRoot().top)
        // The support row belongs only to builds that sell Premium.
        val supportRow = compose.onAllNodesWithTag("footerSupport")
        if (BuildConfig.PREMIUM_ENABLED) {
            // The row appears once the purchase record has been read.
            compose.waitUntil(10_000) { supportRow.fetchSemanticsNodes().size == 1 }
            supportRow[0].assertHeightIsAtLeast(48.dp).assertHasClickAction().performClick()
            compose.runOnIdle { assertEquals(1, support) }
        } else assertEquals(0, supportRow.fetchSemanticsNodes().size)
        val wordmark = compose.onNodeWithText(context.getString(R.string.list_wordmark))
        wordmark.assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.list_by_open_waters)).assertIsDisplayed()
        assert(wordmark.getBoundsInRoot().top > settingsRow.getBoundsInRoot().bottom)
    }

    @Test fun theDownloadsRowAnnouncesItsState() {
        var state: DownloadsRowState by androidx.compose.runtime.mutableStateOf(DownloadsRowState.Downloading(3, 8, failed = false))
        compose.setContent { SlackwaterTheme { DownloadsRow(state) {} } }
        fun spoken(): String? = compose.onNodeWithTag("footerDownloads").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)
        compose.onNodeWithTag("footerDownloads")
            .assertContentDescriptionEquals(context.getString(R.string.footer_downloads_label))
        assertEquals("Downloading, 3 of 8 ready", spoken())

        state = DownloadsRowState.Online(complete = true, failed = false)
        compose.waitForIdle()
        assertEquals("Online, all stations downloaded", spoken())

        state = DownloadsRowState.Offline(remaining = 3, complete = false)
        compose.waitForIdle()
        assertEquals("Offline, 3 still to download", spoken())

        state = DownloadsRowState.Offline(remaining = 0, complete = true)
        compose.waitForIdle()
        assertEquals("Offline, all stations downloaded", spoken())

        // With nothing wanted there is nothing missing to count.
        state = DownloadsRowState.Offline(remaining = 0, complete = false)
        compose.waitForIdle()
        assertEquals("Offline", spoken())

        // Work outside the automatic set has no total, so no "0 of 0".
        state = DownloadsRowState.Downloading(0, 0, failed = false)
        compose.waitForIdle()
        assertEquals("Downloading", spoken())
    }
}
