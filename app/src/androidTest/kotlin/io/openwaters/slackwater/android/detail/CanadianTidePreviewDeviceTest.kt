package io.openwaters.slackwater.android.detail

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.ModelKind
import io.openwaters.slackwater.android.data.ModelStore
import io.openwaters.slackwater.android.data.TideDownloadState
import io.openwaters.slackwater.android.data.TidePreviewDownloads
import io.openwaters.slackwater.android.data.StoredModel
import io.openwaters.slackwater.android.data.UserStateStore
import io.openwaters.slackwater.android.openStation
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in phases use one isolated emulator. Force-stop the app between phases to check process recovery. */
class CanadianTidePreviewDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun explicitRequestAndOfflineReopen() {
        val phase = InstrumentationRegistry.getArguments().getString("previewPhase")
        assumeTrue(phase in listOf("queueOffline", "queueRestricted", "download", "offline"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vancouver = InstrumentationRegistry.getArguments().getString("previewStation") == "vancouver"
        val id = StationId(if (vancouver) "chs-vancouver" else "chs-victoria")
        val name = if (vancouver) "Vancouver" else "Victoria"
        val models = ModelStore.get(context)
        val intents = UserStateStore.get(context).downloads
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openStation(name, if (vancouver) "Burrard Inlet" else "Inner Harbour")
            if (phase == "queueOffline" || phase == "queueRestricted") {
                check(runBlocking { models.read(id, ModelKind.TIDE_PREVIEW) } == StoredModel.Missing)
                val button = context.getString(R.string.detail_download)
                compose.waitUntil(60_000) { compose.onAllNodesWithText(button).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(button).performScrollTo().performClick()
                runBlocking { withTimeout(20_000) { intents.state.first { id in it.getOrThrow().requested } } }
                val expected = if (phase == "queueRestricted") TideDownloadState.DATA_SAVER else TideDownloadState.WAITING_FOR_NETWORK
                compose.waitUntil(20_000) { TidePreviewDownloads.get(context).states.value[id] == expected }
                return
            }
            val record = runBlocking {
                withTimeout(120_000) {
                    models.revision.first { models.read(id, ModelKind.TIDE_PREVIEW) is StoredModel.Ready }
                    (models.read(id, ModelKind.TIDE_PREVIEW) as StoredModel.Ready).record
                }
            }
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("detailTimeline").fetchSemanticsNodes().isNotEmpty() }
            val samples = record.getJSONArray("samples")
            val start = Instant.parse(samples.getJSONObject(0).getString("time"))
            val end = Instant.parse(samples.getJSONObject(samples.length()-1).getString("time"))
            assertTrue(Instant.now() in start..end)
            assertTrue(samples.length() >= 2)
            Log.i("TidePreviewTest", "$name: ${samples.length()} samples, $start through $end ($phase)")
        }
    }
}
