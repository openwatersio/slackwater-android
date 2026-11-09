package io.openwaters.slackwater.android.stations

import androidx.compose.runtime.collectAsState
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
            if (prefs != null) {
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
            val pixels = card.captureToImage().toPixelMap()
            return (0 until pixels.height).sumOf { y ->
                (0 until pixels.width).count { x ->
                    val color = pixels[x, y]
                    color.blue > color.red + 0.4f && color.green > color.red + 0.3f
                }
            }
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
}
