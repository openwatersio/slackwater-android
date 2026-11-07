package io.openwaters.slackwater.android.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.*
import io.openwaters.slackwater.android.stations.StationListModel
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class DetailPreviewExpiryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun foregroundAfterPreviewExpiryWithdrawsChartWithoutModelWrite() = verifyExpiry(resume = true)
    @Test fun nowAfterPreviewExpiryWithdrawsChartWithoutModelWrite() = verifyExpiry(resume = false)

    private fun verifyExpiry(resume: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val station = StationId("chs-abbotts-harbour")
        val store = ModelStore.get(context)
        assumeTrue(runBlocking { store.read(station, ModelKind.TIDE) == StoredModel.Missing &&
            store.read(station, ModelKind.TIDE_PREVIEW) == StoredModel.Missing })
        val then = Instant.now().minusSeconds(10L * 86400)
        val samples = JSONArray()
        for (i in -96..96) samples.put(JSONObject().put("time", then.plusSeconds(i * 900L).toString())
            .put("height", kotlin.math.cos(i / 5.0)))
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        try {
            runBlocking { store.write(station, ModelKind.TIDE_PREVIEW, JSONObject().put("samples", samples)) }
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    StationDetailScreen(StationListModel.get(context), station, then, then.plusSeconds(3600), {}, {})
                }
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("detailTimeline").fetchSemanticsNodes().isNotEmpty() }
            if (resume) compose.runOnIdle {
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
            } else {
                val now = hasContentDescription("Return to now")
                compose.waitUntil(30_000) { compose.onAllNodes(now).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(now).performClick()
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Download for offline use").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("detailTimeline").assertDoesNotExist()
            compose.onNodeWithText("Schedule").assertDoesNotExist()
        } finally {
            runBlocking { store.remove(station, ModelKind.TIDE_PREVIEW) }
        }
    }
}
