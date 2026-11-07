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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.data.CurrentMapFieldSource
import java.time.Instant
import kotlinx.coroutines.runBlocking
import io.openwaters.slackwater.android.core.map.FillCell
import io.openwaters.slackwater.android.core.map.MapPinState
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import java.net.InetAddress
import java.net.ServerSocket
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MapNativeConformanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun selectCenter(expected: StationId, selection: AtomicReference<StationId?>) {
        val native = AtomicReference<MapLibreMap>()
        compose.runOnIdle {
            (compose.activity.window.decorView as ViewGroup).descendants.filterIsInstance<MapView>().single()
                .getMapAsync(native::set)
        }
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

    @Test fun currentLayersShowOnlyCertifiedWaterAndClearWhenPredictionsDisappear() {
        val center = Coordinate(48.50, -123.0)
        val cell = FillCell(listOf(Coordinate(48.48, -123.03), Coordinate(48.48, -122.97),
            Coordinate(48.54, -123.0)), 8.0, 170.0)
        val fields = mutableStateOf<CurrentFieldFeatures?>(
            CurrentMapFields(listOf(cell.copy(speedKn = 0.0)), listOf(cell)).features())
        compose.setContent {
            NativeStationMap(emptyList(), null, null, center, 12.0, 0, 0, { _, _, _, _, _, _ -> },
                {}, {}, modifier = Modifier.fillMaxSize(), localOnly = true, fields = fields.value)
        }
        val native = AtomicReference<MapLibreMap>()
        compose.runOnIdle {
            (compose.activity.window.decorView as ViewGroup).descendants.filterIsInstance<MapView>().single()
                .getMapAsync(native::set)
        }
        fun rendered(lat: Double, lon: Double): List<Double> {
            val map = native.get() ?: return emptyList()
            val point = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(lat, lon))
            return map.queryRenderedFeatures(point, "current-fill", "current-fill-patches")
                .map { it.getNumberProperty("kn").toDouble() }
        }
        compose.waitUntil(20_000) {
            compose.runOnIdle { rendered(center.latitude, center.longitude).toSet() == setOf(0.0, 8.0) }
        }
        compose.runOnIdle {
            val layerIds = native.get().style!!.layers.map { it.id }
            assertTrue(layerIds.indexOf("current-fill-patches") > layerIds.indexOf("current-fill"))
            assertTrue(rendered(48.52, -122.98).isEmpty())
            fields.value = null
        }
        compose.waitUntil(10_000) {
            compose.runOnIdle { rendered(center.latitude, center.longitude).isEmpty() }
        }
    }

    @Test fun doddDirectionIsVisibleAndSelectableBesideTheStationDot() {
        val id = StationId("chs-dodd-narrows")
        val center = Coordinate(49.135, -123.817)
        val station = StationIdentity(id, "Dodd Narrows", "", center.latitude, center.longitude, StationKind.CanadianCurrent)
        val fields = mutableStateOf<CurrentFieldFeatures?>(CurrentMapFields(emptyList(), emptyList(), center to 0.0).features())
        val selection = AtomicReference<StationId?>()
        compose.setContent {
            NativeStationMap(listOf(StationMapPin(station, MapPinState())), null, null, center, 12.5, 0, 0,
                { _, _, _, _, _, _ -> }, selection::set, {}, modifier = Modifier.fillMaxSize().testTag("map"),
                localOnly = true, fields = fields.value)
        }
        val native = AtomicReference<MapLibreMap>()
        compose.runOnIdle {
            (compose.activity.window.decorView as ViewGroup).descendants.filterIsInstance<MapView>().single()
                .getMapAsync(native::set)
        }
        var arrowTap = androidx.compose.ui.geometry.Offset.Zero
        compose.waitUntil(20_000) {
            compose.runOnIdle {
                val map = native.get() ?: return@runOnIdle false
                val origin = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(center.latitude, center.longitude))
                if (map.queryRenderedFeatures(origin, "station-dots").isEmpty()) return@runOnIdle false
                val radius = (60 * compose.activity.resources.displayMetrics.density).toInt()
                for (dy in radius downTo 1) {
                    val point = android.graphics.PointF(origin.x, origin.y - dy)
                    if (map.queryRenderedFeatures(point, "station-vectors").any { it.getStringProperty("id") == id.value } &&
                        map.queryRenderedFeatures(point, "station-dots").isEmpty()) {
                        arrowTap = androidx.compose.ui.geometry.Offset(point.x, point.y)
                        return@runOnIdle true
                    }
                }
                false
            }
        }
        compose.onNodeWithTag("map").performTouchInput { click(arrowTap) }
        compose.waitUntil(5_000) { selection.get() == id }
        compose.runOnIdle { fields.value = null }
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                native.get().queryRenderedFeatures(android.graphics.PointF(arrowTap.x, arrowTap.y), "station-vectors").isEmpty()
            }
        }
    }

    @Test fun shippedSeymourPatchDisappearsWhenTheSelectedMinuteIsOutsideCoverage() {
        val time = Instant.parse("2026-11-07T16:00:00Z")
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult =
                if (id.value == "chs-seymour-narrows") BoundedPredictions.current(id, span,
                    listOf(CurrentPoint(time, 3.0), CurrentPoint(time.plusSeconds(1800), -1.0)), 30.0, 210.0)
                else PredictionResult.Unavailable(PredictionResult.Reason.MISSING_DOWNLOAD)
        }
        val source = CurrentMapFieldSource(compose.activity) { id, at -> repository.load(id, TimeSpan(at, at), PredictionPurpose.DISPLAY) }
        val covered = runBlocking { source.evaluate(time) }
        val cell = covered.patches.first()
        val center = Coordinate(cell.polygon.map { it.latitude }.average(), cell.polygon.map { it.longitude }.average())
        val fields = mutableStateOf(covered.features())
        compose.setContent {
            NativeStationMap(emptyList(), null, null, center, 14.0, 0, 0, { _, _, _, _, _, _ -> },
                {}, {}, modifier = Modifier.fillMaxSize(), localOnly = true, fields = fields.value)
        }
        val native = AtomicReference<MapLibreMap>()
        compose.runOnIdle {
            (compose.activity.window.decorView as ViewGroup).descendants.filterIsInstance<MapView>().single()
                .getMapAsync(native::set)
        }
        fun speed(): Double? {
            val map = native.get() ?: return null
            val point = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(center.latitude, center.longitude))
            return map.queryRenderedFeatures(point, "current-fill-patches").firstOrNull()?.getNumberProperty("kn")?.toDouble()
        }
        compose.waitUntil(45_000) { compose.runOnIdle { speed()?.let { kotlin.math.abs(it - cell.speedKn) < 1e-5 } == true } }
        val gap = runBlocking { source.evaluate(time.plusSeconds(900)).features() }
        compose.runOnIdle { fields.value = gap }
        compose.waitUntil(45_000) { compose.runOnIdle { speed() == null } }
    }

    @Test fun localPinsSurviveStalledStyleAndGlyphRequestsAcrossReloadFilterAndCameraChanges() {
        val first = StationIdentity(StationId("first"), "First", "", 48.5, -123.0, StationKind.Tide)
        val second = first.copy(id = StationId("restricted"), latitude = 44.0, kind = StationKind.Restricted)
        val pin = mutableStateOf(first)
        val camera = mutableIntStateOf(0)
        val reload = mutableIntStateOf(0)
        val status = AtomicReference<BasemapStatus>()
        val viewport = AtomicReference<Coordinate>()
        val selection = AtomicReference<StationId?>()
        val file = File.createTempFile("slackwater-map", ".json", compose.activity.cacheDir)
        // Listening without answering leaves TLS negotiation pending until the client cancels.
        fun stalledServer() = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val stalledStyle = stalledServer()
        val stalledGlyphs = stalledServer()
        val style = mutableStateOf("https://127.0.0.1:${stalledStyle.localPort}/style.json")
        try {
            compose.setContent {
                val station = pin.value
                NativeStationMap(
                    pins = listOf(StationMapPin(station, MapPinState())),
                    selectedId = null, fix = null, cameraTarget = Coordinate(station.latitude, station.longitude),
                    cameraZoom = 11.0, cameraRequest = camera.intValue, styleRequest = reload.intValue,
                    onViewport = { _, _, _, _, _, center -> viewport.set(center) },
                    onSelect = selection::set, onBasemapStatus = status::set,
                    modifier = Modifier.fillMaxSize().testTag("map"), styleUrl = style.value,
                )
            }
            compose.waitUntil(8_000) { status.get() == BasemapStatus.Slow }
            val native = AtomicReference<MapLibreMap>()
            compose.runOnIdle {
                (compose.activity.window.decorView as ViewGroup).descendants.filterIsInstance<MapView>().single()
                    .getMapAsync(native::set)
            }
            selectCenter(first.id, selection)
            stalledStyle.accept().close()
            compose.runOnIdle {
                assertTrue(native.get().uiSettings.isAttributionEnabled)
                assertTrue(native.get().uiSettings.isLogoEnabled)
            }
            compose.onNodeWithTag("map").performTouchInput { swipeLeft() }
            compose.waitUntil(10_000) {
                viewport.get()?.let { kotlin.math.abs(it.longitude - first.longitude) > 0.001 } == true
            }
            file.writeText("""{"version":8,"glyphs":"https://127.0.0.1:${stalledGlyphs.localPort}/{fontstack}/{range}.pbf","sources":{},"layers":[{"id":"ground","type":"background"}]}""")
            compose.runOnIdle {
                style.value = android.net.Uri.fromFile(file).toString()
                pin.value = second
                camera.intValue++
                reload.intValue++
            }
            compose.waitUntil(20_000) { viewport.get()?.let { kotlin.math.abs(it.latitude - second.latitude) < 0.001 } == true }
            compose.waitUntil(8_000) {
                compose.runOnIdle { native.get().style?.getLayer("station-readings") != null }
            }
            selectCenter(second.id, selection)
            stalledGlyphs.accept().close()
            assertEquals(BasemapStatus.Ready, status.get())
        } finally {
            stalledStyle.close()
            stalledGlyphs.close()
            file.delete()
        }
    }

    @Test fun slowRetryCanBeStoppedOrFinishAfterTheFirstEntryDeadline() {
        val status = AtomicReference<BasemapStatus>()
        val request = mutableIntStateOf(0)
        val localOnly = mutableStateOf(false)
        val base = "https://map-test.invalid/${UUID.randomUUID()}"
        val pending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.host != "map-test.invalid") return@addInterceptor chain.proceed(chain.request())
            val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            if (url.encodedPath.endsWith("/0")) {
                response.code(404).message("Missing").body("".toResponseBody()).build()
            } else {
                if (url.encodedPath.endsWith("/2")) pending.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                response.code(200).message("OK").body(
                    """{"version":8,"sources":{},"layers":[{"id":"hosted-ground","type":"background"}]}""".toResponseBody()
                ).build()
            }
        }.build()
        compose.runOnIdle { org.maplibre.android.MapLibre.getInstance(compose.activity) }
        HttpRequestUtil.setOkHttpClient(client)
        try {
            val station = StationIdentity(StationId("test"), "Test", "", 48.5, -123.0, StationKind.Tide)
            val selected = AtomicReference<StationId?>()
            compose.setContent {
                NativeStationMap(pins = listOf(StationMapPin(station, MapPinState())), selectedId = null,
                    fix = null, cameraTarget = Coordinate(station.latitude, station.longitude), cameraZoom = 11.0,
                    cameraRequest = 0, styleRequest = request.intValue, onViewport = { _, _, _, _, _, _ -> },
                    onSelect = selected::set, onBasemapStatus = status::set, modifier = Modifier.fillMaxSize().testTag("map"),
                    styleUrl = "$base/${request.intValue}", localOnly = localOnly.value,
                    retryTimeoutMillis = if (request.intValue == 1) 1_000L else 30_000L)
            }
            compose.waitUntil(8_000) { status.get() == BasemapStatus.Unavailable }
            compose.runOnIdle { request.intValue++ }
            compose.waitUntil(8_000) { status.get() == BasemapStatus.Retrying }
            compose.waitUntil(8_000) { status.get() == BasemapStatus.Slow }
            selectCenter(station.id, selected)
            compose.runOnIdle { selected.set(null); localOnly.value = true }
            compose.waitUntil(8_000) { status.get() == BasemapStatus.LocalOnly }
            selectCenter(station.id, selected)
            compose.runOnIdle {
                selected.set(null)
                localOnly.value = false
                request.intValue++
            }
            compose.waitUntil(8_000) { pending.count == 0L }
            android.os.SystemClock.sleep(3_000)
            assertEquals(BasemapStatus.Retrying, status.get())
            release.countDown()
            compose.waitUntil(8_000) { status.get() == BasemapStatus.Ready }
            selectCenter(station.id, selected)
        } finally {
            release.countDown()
            HttpRequestUtil.setOkHttpClient(null)
        }
    }
}
