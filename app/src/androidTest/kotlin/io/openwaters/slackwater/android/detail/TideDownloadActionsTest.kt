package io.openwaters.slackwater.android.detail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.data.TideDownloadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TideDownloadActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun requestProgressRetryAndCancel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val status = mutableStateOf<TideDownloadState?>(null)
        var requests = 0
        var cancellations = 0
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                TideDownloadActions(status.value, { requests++ }, { cancellations++ })
            }
        }
        fun button(resource: Int) = compose.onNodeWithText(context.getString(resource))
        button(R.string.detail_download).performClick()
        for ((state, label) in listOf(
            TideDownloadState.QUEUED to R.string.detail_queued,
            TideDownloadState.WAITING_FOR_NETWORK to R.string.tide_download_network,
            TideDownloadState.DATA_SAVER to R.string.tide_download_restricted,
            TideDownloadState.DOWNLOADING to R.string.tide_download_active,
            TideDownloadState.RETRY to R.string.tide_download_failed,
        )) {
            compose.runOnIdle { status.value = state }
            button(label).assertExists()
            if (state == TideDownloadState.DOWNLOADING) {
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
            }
        }
        button(R.string.table_sheet_retry).performClick()
        button(R.string.alert_cancel).performClick()
        compose.runOnIdle {
            assertEquals(2, requests)
            assertEquals(1, cancellations)
        }
    }
}
