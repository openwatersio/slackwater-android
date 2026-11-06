package io.openwaters.slackwater.android.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import android.view.ViewGroup
import androidx.core.view.descendants
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.openwaters.slackwater.android.core.map.MapPinState
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class MapNativeConformanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun localPinsRemainSelectableAcrossFailedStyleReloadFilterAndCameraChanges() {
        val first = StationIdentity(StationId("first"), "First", "", 48.5, -123.0, StationKind.Tide)
        val second = first.copy(id = StationId("restricted"), latitude = 44.0, kind = StationKind.Restricted)
        val pin = mutableStateOf(first)
        val camera = mutableIntStateOf(0)
        val reload = mutableIntStateOf(0)
        val failed = AtomicBoolean()
        val viewport = AtomicReference<Coordinate>()
        val selection = AtomicReference<StationId?>()
        val file = File.createTempFile("slackwater-map", ".json", compose.activity.cacheDir)
        file.delete()
        try {
            compose.setContent {
                val station = pin.value
                NativeStationMap(
                    pins = listOf(StationMapPin(station, MapPinState())),
                    selectedId = null, fix = null, cameraTarget = Coordinate(station.latitude, station.longitude),
                    cameraZoom = 10.0, cameraRequest = camera.intValue, styleRequest = reload.intValue,
                    onViewport = { _, _, _, _, _, center -> viewport.set(center) },
                    onSelect = selection::set, onStyleFailure = failed::set,
                    modifier = Modifier.fillMaxSize().testTag("map"), styleUrl = android.net.Uri.fromFile(file).toString(),
                )
            }
            compose.waitUntil(20_000) { failed.get() }
            val native = AtomicReference<MapLibreMap>()
            compose.runOnIdle {
                (compose.activity.window.decorView as ViewGroup).descendants.filterIsInstance<MapView>().single()
                    .getMapAsync(native::set)
            }
            fun selectCenter(expected: StationId) {
                compose.waitUntil(20_000) {
                    compose.runOnIdle {
                        val map = native.get() ?: return@runOnIdle false
                        val target = map.cameraPosition.target ?: return@runOnIdle false
                        val xy = map.projection.toScreenLocation(target)
                        val bounds = android.graphics.RectF(xy.x - 16, xy.y - 16, xy.x + 16, xy.y + 16)
                        map.queryRenderedFeatures(bounds, *stationHitLayers).any { it.getStringProperty("id") == expected.value }
                    }
                }
                compose.onNodeWithTag("map").performTouchInput { click(center) }
                compose.waitUntil(5_000) { selection.get() == expected }
            }
            selectCenter(first.id)
            compose.runOnIdle {
                assertTrue(native.get().uiSettings.isAttributionEnabled)
                assertTrue(native.get().uiSettings.isLogoEnabled)
            }
            compose.onNodeWithTag("map").performTouchInput { swipeLeft() }
            compose.waitUntil(10_000) {
                viewport.get()?.let { kotlin.math.abs(it.longitude - first.longitude) > 0.001 } == true
            }
            file.writeText("""{"version":8,"sources":{},"layers":[{"id":"ground","type":"background"}]}""")
            compose.runOnIdle {
                pin.value = second
                camera.intValue++
                reload.intValue++
            }
            compose.waitUntil(20_000) { viewport.get()?.let { kotlin.math.abs(it.latitude - second.latitude) < 0.001 } == true }
            selectCenter(second.id)
            assertFalse(failed.get())
        } finally {
            file.delete()
        }
    }
}
