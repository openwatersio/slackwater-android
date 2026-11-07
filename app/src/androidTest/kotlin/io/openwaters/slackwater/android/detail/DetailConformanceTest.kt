package io.openwaters.slackwater.android.detail

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.*
import io.openwaters.slackwater.android.stations.StationListModel
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DetailConformanceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-11-06T18:00:00Z")

    @Test fun tidePage() = scene("tide", "noaa/9449880")
    @Test fun measuredCurrentPage() = scene("current", "current:noaa/PCT3896")
    @Test fun longStationName() = scene("long-label", "ticon/sunnyvale_east_channel_below_caribbean_drive-sun-usa-cm")
    @Test fun vectorCurrentAtSlack() {
        val id = StationId("current:noaa/CHB9904")
        val span = TimeSpan(now, now.plusSeconds(86400))
        val ready = runBlocking { AndroidPredictionRepository(context).load(id, span, PredictionPurpose.DISPLAY) } as PredictionResult.Ready
        val current = ready.series as PredictionSeries.Current
        assertTrue(current.hasCrossAxis)
        val slack = current.events.first { it.kind == CurrentEventKind.SLACK }
        scene("vector-slack", id.value, slack.time)
    }

    @Test fun completedBoundedGateShowsItsBoundWithoutRequestingMoreData() =
        gatePage("chs-blackney-passage", "Blackney Passage", 210, 45, provisional = false)

    @Test fun provisionalGateShowsItsBoundAndRequestsMoreData() =
        gatePage("chs-johnstone-strait-central", "Johnstone Strait - Central", 60, 35, provisional = true)

    private fun gatePage(rawId: String, name: String, days: Int, minutes: Int, provisional: Boolean) {
        val store = ModelStore.get(context)
        val id = StationId(rawId)
        val previous = runBlocking { store.read(id, ModelKind.CURRENT) }
        assertTrue(previous is StoredModel.Ready || previous is StoredModel.Missing)
        try {
            val fit = JSONObject("""{"offset":1.387,"constituents":[{"name":"M2","amplitude":1.5,"phase":10}]}""")
                .put("recipe", io.openwaters.slackwater.ChsCurrentFit.STAMP).put("fitDays", days)
                .put("floodDirection", 20).put("ebbDirection", 215)
            runBlocking { store.write(id, ModelKind.CURRENT, fit) }
            compose.setContent { StationDetailScreen(StationListModel.get(context), id, now, null, {}, {}) }
            val bound = context.getString(io.openwaters.slackwater.android.R.string.detail_bound, name, minutes)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(bound).fetchSemanticsNodes().isNotEmpty() }
            val notice = compose.onNodeWithText(context.getString(io.openwaters.slackwater.android.R.string.detail_more_data))
            if (provisional) notice.assertExists() else notice.assertDoesNotExist()
        } finally {
            runBlocking {
                if (previous is StoredModel.Ready) store.write(id, ModelKind.CURRENT, previous.record)
                else store.remove(id, ModelKind.CURRENT)
            }
        }
    }

    @Test fun derivedGatePage() {
        val store = ModelStore.get(context)
        val id = StationId("chs-point-atkinson")
        val previous = runBlocking { store.read(id, ModelKind.TIDE) }
        assertTrue(previous is StoredModel.Ready || previous is StoredModel.Missing)
        try {
            runBlocking { store.write(id, ModelKind.TIDE, JSONObject("""{"offset":1.387,"constituents":[{"name":"M2","amplitude":1.5,"phase":10}]}""")) }
            scene("derived", "chs-malibu-rapids")
            compose.onNodeWithText(context.getString(io.openwaters.slackwater.android.R.string.detail_context, "9 kn flood & ebb")).assertExists()
        } finally {
            runBlocking {
                if (previous is StoredModel.Ready) store.write(id, ModelKind.TIDE, previous.record)
                else store.remove(id, ModelKind.TIDE)
            }
        }
    }

    @Test fun unavailableStationOffersHelpAndNearbyWithoutAStarOrSchedule() {
        compose.setContent { StationDetailScreen(StationListModel.get(context), StationId("ticon/aigio-aig-grc-cmems"), now, null, {}, {}) }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Predictions unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Share station details").assertExists()
        compose.onNodeWithText("Nearby").assertExists()
        compose.onNodeWithText("Schedule").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add to favorites").assertDoesNotExist()
        compose.onNodeWithTag("detailTimeline").assertDoesNotExist()
    }

    @Test fun neverDownloadedOnlineGateHasAnAlternativeAndNoWeekBar() {
        compose.setContent { StationDetailScreen(StationListModel.get(context), StationId("chs-arran-rapids"), now, null, {}, {}) }
        compose.waitUntil(60_000) { compose.onAllNodesWithText("instead", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No saved predictions cover this week.").assertExists()
        compose.onNodeWithText("Download for offline use").assertExists()
        compose.onNodeWithTag("detailTimeline").assertDoesNotExist()
        compose.onNodeWithText("Schedule").assertDoesNotExist()
    }

    @Test fun norwegianFooterNamesItsHarmonicPublisher() {
        compose.setContent { StationDetailScreen(StationListModel.get(context), StationId("kartverket/ANX"), now, null, {}, {}) }
        val credit = hasText("Harmonic constants from Kartverket", substring = true)
        compose.waitUntil(60_000) { compose.onAllNodes(credit).fetchSemanticsNodes().isNotEmpty() }
        // Nearby cards can grow while their predictions load above the footer.
        compose.waitUntil(10_000) { compose.onNode(credit).performScrollTo().isDisplayed() }
        compose.onNode(credit).assertIsDisplayed()
    }

    @Test fun waitingPageFillsInWithoutReopeningTheStation() {
        val id = StationId("chs-abbotts-harbour")
        val store = ModelStore.get(context)
        val previous = runBlocking { store.read(id, ModelKind.TIDE) }
        val preview = runBlocking { store.read(id, ModelKind.TIDE_PREVIEW) }
        org.junit.Assume.assumeTrue(previous is StoredModel.Missing && preview is StoredModel.Missing)
        try {
            compose.setContent { StationDetailScreen(StationListModel.get(context), id, now, null, {}, {}) }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Download for offline use").fetchSemanticsNodes().isNotEmpty() }
            val samples = org.json.JSONArray()
            for (i in -8..8) samples.put(JSONObject().put("time", now.plusSeconds(i * 900L).toString()).put("height", kotlin.math.cos(i / 4.0)))
            runBlocking { store.write(id, ModelKind.TIDE_PREVIEW, JSONObject().put("samples", samples)) }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Predictions available through", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(io.openwaters.slackwater.android.R.string.tide_download_saved)).assertExists()
            compose.onNodeWithTag("detailTimeline").assertExists()
            compose.onNodeWithText("Set an alert").assertDoesNotExist()
            runBlocking { store.write(id, ModelKind.TIDE, JSONObject("""{"offset":1.0,"constituents":[{"name":"M2","amplitude":1.2,"phase":10}]}""")) }
            compose.waitUntil(60_000) { compose.onAllNodesWithText("Set an alert", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Predictions available through", substring = true).assertDoesNotExist()
        } finally {
            runBlocking { store.remove(id, ModelKind.TIDE); store.remove(id, ModelKind.TIDE_PREVIEW) }
        }
    }

    private fun scene(name: String, id: String, at: Instant = now) {
        compose.setContent {
            MaterialTheme { StationDetailScreen(StationListModel.get(context), StationId(id), now, at, {}, {}) }
        }
        val plot = compose.onNodeWithTag("detailTimeline")
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("detailTimeline").fetchSemanticsNodes().size == 1 }
        plot.assertIsDisplayed()
        val spoken = plot.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue("Missing chart reading on $name", spoken.isNotBlank())
        if (name == "vector-slack") assertTrue(spoken, spoken.contains("slack", ignoreCase = true))
        if (name == "derived") assertFalse(spoken, spoken.contains(" kn"))
        if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            compose.waitUntil(60_000) {
                compose.mainClock.advanceTimeBy(64)
                compose.onAllNodesWithTag("detailSkyReady", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("detailCommentary", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val image = instrumentation.uiAutomation.takeScreenshot()
            val dir = File(context.getExternalFilesDir(null), "detail-evidence").apply { mkdirs() }
            File(dir, "$name-${image.width}x${image.height}.png").outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
        }
    }
}
