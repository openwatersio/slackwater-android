package io.openwaters.slackwater.android.stations

import androidx.compose.runtime.collectAsState
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.SlackwaterTheme
import io.openwaters.slackwater.android.data.BundledStationCatalog
import io.openwaters.slackwater.android.data.UserStateStore
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.rules.TemporaryFolder
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StationCardThresholdTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val files = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun stopStore() = scope.cancel()

    @Test fun mountedCurrentCardRedrawsItsSlackWindowBeforeTheNextTick() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = StationListModel.get(context)
        val id = StationId("current:noaa/PUG1527")
        val station = runBlocking { withTimeout(30_000) { model.catalog.filterNotNull().first().byId.getValue(id) } }
        val now = Instant.parse("2026-06-01T12:00:00Z")
        val store = ReadingPreferencesStore.open(File(files.root, "reading.preferences_pb"), scope)
        runBlocking { store.setSlackWindowKnots(0.0) }
        val loaded = AtomicReference<Double>()
        compose.setContent {
            val prefs = store.preferences.collectAsState(initial = null).value?.getOrThrow()
            if (prefs != null) SlackwaterTheme {
                StationCard(station, null, now, prefs, { at, threshold ->
                    model.card(id, at, threshold).also { data ->
                        checkNotNull(data.curve) { "Missing Tacoma Narrows curve at $at" }
                        loaded.set(threshold)
                    }
                }, onOpen = null)
            }
        }

        val card = compose.onNodeWithContentDescription(station.name, substring = true)
        fun bluePixels(threshold: Double): Int {
            compose.waitUntil(30_000) { loaded.get() == threshold }
            compose.waitForIdle()
            return card.blueWindowPixels()
        }

        val off = bluePixels(0.0)
        runBlocking { store.setSlackWindowKnots(0.5) }
        val narrow = bluePixels(0.5)
        assertTrue("Enabling the threshold should draw a blue slack window", narrow > off)
        runBlocking { store.setStationThresholdDeltaKn(id, 0.5) }
        assertTrue("Increasing the threshold should widen the blue window", bluePixels(1.0) > narrow)
        runBlocking { store.setSlackWindowKnots(0.0) }
        assertEquals(off, bluePixels(0.0))
    }
    private fun SemanticsNodeInteraction.blueWindowPixels(): Int {
        val pixels = captureToImage().toPixelMap()
        return (0 until pixels.height).sumOf { y ->
            (0 until pixels.width).count { x ->
                val color = pixels[x, y]
                color.blue > color.red + 0.4f && color.green > color.red + 0.3f
            }
        }
    }

    @Test fun listCardReflectsComfortCurrentChangedThroughSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = StationId("current:noaa/PUG1527")
        val places = UserStateStore.open(File(files.root, "places.preferences_pb"), BundledStationCatalog(context.assets), scope)
        val store = ReadingPreferencesStore.open(File(files.root, "reading.preferences_pb"), scope)
        runBlocking { store.setSlackWindowKnots(0.0) }
        val model = StationListModel(context, scope = scope, places = places, readingStore = store)
        val fixed = Instant.parse("2026-06-01T12:00:00Z")
        val station = runBlocking {
            withTimeout(10_000) { model.readingPreferences.first { it.slackWindowKnots == 0.0 } }
            places.recordVisit(id)
            // With no location fix, Boston anchors Near Me and Tacoma stays in Recents.
            places.recordVisit(StationId("noaa/8443970"))
            withTimeout(30_000) { model.catalog.filterNotNull().first().byId.getValue(id) }
        }
        val settings = context.getString(R.string.settings_title)
        val card = compose.onNodeWithContentDescription(station.name, substring = true)

        compose.setContent {
            SlackwaterTheme {
                Box(Modifier.widthIn(max = 390.dp)) {
                    StationsHome(onCredits = {}, model = model, clock = { fixed }) { openSettings ->
                        TextButton(onClick = openSettings) { Text(settings) }
                    }
                }
            }
        }
        val list = compose.onNode(hasScrollAction())
        compose.waitUntil("List includes the Settings footer", 60_000) {
            compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().any { node ->
                SemanticsProperties.IndexForKey in node.config && node.config[SemanticsProperties.IndexForKey]("footer") >= 0
            }
        }
        val slack = context.getString(R.string.card_slack)
        val loadedCard = hasContentDescription(station.name, substring = true) and
            hasContentDescription(slack, substring = true, ignoreCase = true)
        compose.waitUntil("Settings and the loaded Tacoma card are both visible", 30_000) {
            list.performScrollToKey("footer")
            compose.onAllNodes(hasText(settings) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(loadedCard).fetchSemanticsNodes().isNotEmpty()
        }
        val mounted = card.fetchSemanticsNode().id
        val off = card.blueWindowPixels()
        fun changeThreshold(increase: Boolean) {
            compose.onNodeWithText(settings).performClick()
            val button = compose.onNodeWithContentDescription(context.getString(
                if (increase) R.string.settings_increase else R.string.settings_decrease))
            button.performScrollTo()
            for (step in 1..5) {
                button.performClick()
                val target = (if (increase) step else 5 - step) / 10.0
                compose.waitUntil("Comfort current reaches $target kn", 10_000) {
                    model.readingPreferences.value.slackWindowKnots == target
                }
            }
            compose.onNodeWithText(context.getString(R.string.close)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            assertEquals(mounted, card.fetchSemanticsNode().id)
        }
        changeThreshold(true)
        compose.waitUntil(30_000) { card.blueWindowPixels() > off }
        changeThreshold(false)
        compose.waitUntil(30_000) { card.blueWindowPixels() == off }
    }
}
