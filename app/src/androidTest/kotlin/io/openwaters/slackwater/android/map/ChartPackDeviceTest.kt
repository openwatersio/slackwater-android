package io.openwaters.slackwater.android.map

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.descendants
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.map.ChartPackSpec
import io.openwaters.slackwater.android.core.map.desiredChartPacks
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.BundledStationCatalog
import io.openwaters.slackwater.android.data.ModelKind
import io.openwaters.slackwater.android.data.ModelStore
import io.openwaters.slackwater.android.data.StoredModel
import io.openwaters.slackwater.android.data.UserStateStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

class ChartPackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // chartPhase=download and chartPhase=offline allow the same check across process death.
    // chartLive=true uses hosted Fiord. chartFull=true uses the production Vancouver footprint.
    @Test fun downloadedStyleRendersAcrossBothOfflineZoomSeams() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("chartPhase")
        val live = args.getString("chartLive") == "true"
        val full = args.getString("chartFull") == "true"
        require(!full || live)
        val specs = if (full) productionSpecs() else listOf(0 to 4, 5 to 8, 9 to 12).map { (min, max) ->
            ChartPackSpec("validation/$min", 49.23, -123.16, 49.32, -123.07, min, max)
        }.toSet()
        val savedIds = File(compose.activity.cacheDir, "chart-test-ids-$full.json")
        val prefix = "https://chart-fixture.invalid/" + (args.getString("chartRun") ?: UUID.randomUUID())
        val style = if (live) BASEMAP_STYLE_URL else "$prefix/style.json"
        val regions = mutableListOf<OfflineRegion>()
        val requests = ConcurrentHashMap.newKeySet<String>()
        val online = AtomicBoolean(true)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (!online.get()) throw IOException("offline test")
            requests.add(url.encodedPath)
            if (live || url.host != "chart-fixture.invalid") return@addInterceptor chain.proceed(chain.request())
            val data = fixture(url.encodedPath, prefix)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(data.toResponseBody()).build()
        }.build()
        val storage = compose.runOnIdle {
            MapLibre.getInstance(compose.activity)
            HttpRequestUtil.setOkHttpClient(client)
            OfflineManager.getInstance(compose.activity)
        }
        try {
            if (live && phase == "remove") {
                removeLivePacks(storage, specs)
                return
            }
            if (phase != "offline") {
                if (live) {
                    reconcileLive(specs)
                    savedIds.writeText(regionIds(storage, specs))
                } else {
                    for (spec in specs) download(storage, style, spec, regions)
                }
                Log.i("ChartPackTest", "Requests: tiles=${requests.count { it.contains("/tiles/") || it.endsWith(".pbf") && !it.contains("/fonts/") || it.contains("/ne2sr/") }}, glyphs=${requests.count { it.contains("/fonts/") }}, sprite=${requests.filter { it.contains("/sprite") }}")
                assertTrue("No glyph ranges requested", requests.any { it.contains("/fonts/") })
                assertTrue("No sprite image requested", requests.any { it.contains("/sprite") && it.endsWith(".png") })
                assertTrue("No sprite index requested", requests.any { it.contains("/sprite") && it.endsWith(".json") })
                assertTrue("No tiles requested", requests.any { it.contains("/tiles/") || it.contains("/ne2sr/") })
                clearAmbient(storage)
                if (phase == "download") return
            }
            online.set(false)
            compose.runOnIdle { MapLibre.setConnected(false) }
            if (live && phase == "offline") {
                reconcileLive(specs)
                assertEquals(savedIds.readText(), regionIds(storage, specs))
            }
            render(style, live)
        } finally {
            compose.runOnIdle {
                MapLibre.setConnected(null)
                HttpRequestUtil.setOkHttpClient(null)
            }
            if (phase != "download") regions.forEach { region ->
                val done = CompletableFuture<Unit>()
                compose.runOnIdle { region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() { done.complete(Unit) }
                    override fun onError(error: String) { done.completeExceptionally(IOException(error)) }
                }) }
                done.get(20, TimeUnit.SECONDS)
            }
        }
    }

    private fun productionSpecs(): Set<ChartPackSpec> {
        val station = runBlocking { BundledStationCatalog(compose.activity.assets).identities() }
            .single { it.id == StationId("chs-vancouver") }
        return desiredChartPacks(Coordinate(49.278, -123.120), listOf(station))
    }

    private fun reconcileLive(specs: Set<ChartPackSpec>) {
        val start = SystemClock.elapsedRealtime()
        val manager = compose.runOnIdle { ChartPackManager.get(compose.activity).also {
            it.setDesired(specs)
            it.resume()
        } }
        var logged = start
        compose.waitUntil(600_000) {
            val now = SystemClock.elapsedRealtime()
            if (now - logged >= 30_000) {
                Log.i("ChartPackTest", "After ${now - start} ms: ${manager.summary.value}")
                logged = now
            }
            manager.summary.value.ready == specs.size
        }
        Log.i("ChartPackTest", "Ready in ${SystemClock.elapsedRealtime() - start} ms: ${manager.summary.value}")
    }

    private fun regionIds(storage: OfflineManager, specs: Set<ChartPackSpec>): String {
        val rows = listRegions(storage)
        val ids = rows.associate { JSONObject(it.metadata.decodeToString()).getString("chart") to it.id }
        assertEquals(specs.map { it.key }.toSet(), ids.keys)
        assertEquals(ids.size, rows.size)
        return JSONObject(ids.toSortedMap()).toString().also { Log.i("ChartPackTest", "Region IDs: $it") }
    }

    private fun removeLivePacks(storage: OfflineManager, specs: Set<ChartPackSpec>) = runBlocking {
        val places = UserStateStore.get(compose.activity)
        val models = ModelStore.get(compose.activity)
        val id = StationId("chs-chart-removal-fixture")
        try {
            places.setFavorite(id, true)
            places.recordVisit(id)
            models.write(id, ModelKind.TIDE, JSONObject().put("offset", 2.5))
            val before = places.places.first().getOrThrow()
            assertTrue(id in before.favorites && id in before.recents)
            val verified = JSONObject(regionIds(storage, specs))
            val ids = verified.keys().asSequence().map { verified.getLong(it) }.toSet()
            compose.runOnIdle { ChartPackManager.get(compose.activity).remove() }
            compose.waitUntil(20_000) { listRegions(storage).none { it.id in ids } }
            assertEquals(before, places.places.first().getOrThrow())
            val record = models.read(id, ModelKind.TIDE) as StoredModel.Ready
            assertEquals(2.5, record.record.getDouble("offset"), 0.0)
            compose.runOnIdle { ChartPackManager.get(compose.activity).resume() }
        } finally {
            places.forgetFavorite(id)
            places.removeRecent(id)
            models.remove(id, ModelKind.TIDE)
        }
    }

    private fun listRegions(storage: OfflineManager): List<OfflineRegion> {
        val result = CompletableFuture<List<OfflineRegion>>()
        compose.runOnIdle { storage.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) { result.complete(offlineRegions.orEmpty().toList()) }
            override fun onError(error: String) { result.completeExceptionally(IOException(error)) }
        }) }
        return result.get(20, TimeUnit.SECONDS)
    }

    private fun clearAmbient(storage: OfflineManager) {
        val cleared = CompletableFuture<Unit>()
        compose.runOnIdle { storage.clearAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { cleared.complete(Unit) }
            override fun onError(message: String) { cleared.completeExceptionally(IOException(message)) }
        }) }
        cleared.get(20, TimeUnit.SECONDS)
    }

    private fun download(storage: OfflineManager, style: String, spec: ChartPackSpec, regions: MutableList<OfflineRegion>) {
        val done = CompletableFuture<OfflineRegionStatus>()
        compose.runOnIdle {
            val bounds = LatLngBounds.from(spec.north, spec.east, spec.south, spec.west)
            val definition = OfflineTilePyramidRegionDefinition(style, bounds, spec.minZoom.toDouble(), spec.maxZoom.toDouble(), 1f)
            storage.createOfflineRegion(definition, spec.key.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onError(error: String) { done.completeExceptionally(IOException(error)) }
                override fun onCreate(offlineRegion: OfflineRegion) {
                    regions.add(offlineRegion)
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (status.isComplete && status.isRequiredResourceCountPrecise && status.completedTileCount > 0) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                done.complete(status)
                            }
                        }
                        override fun onError(error: OfflineRegionError) { done.completeExceptionally(IOException(error.message)) }
                        override fun mapboxTileCountLimitExceeded(limit: Long) { done.completeExceptionally(IOException("quota $limit")) }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
            })
        }
        val status = done.get(60, TimeUnit.SECONDS)
        Log.i("ChartPackTest", "${spec.key}: ${status.completedTileCount} tiles, ${status.completedResourceCount} resources")
    }

    private fun render(style: String, live: Boolean) {
        val center = Coordinate(49.278, -123.120)
        compose.setContent {
            NativeStationMap(emptyList(), null, null, center, 4.0, 0, 0, { _, _, _, _, _, _ -> },
                {}, {}, Modifier.size(180.dp), styleUrl = style)
        }
        val ready = CompletableFuture<MapLibreMap>()
        compose.waitUntil(20_000) {
            compose.runOnIdle {
                val view = (compose.activity.window.decorView as ViewGroup).descendants.filterIsInstance<MapView>().firstOrNull()
                view?.getMapAsync { if (it.style?.uri == style) ready.complete(it) }
                ready.isDone
            }
        }
        val map = ready.get()
        for ((i, zoom) in listOf(4, 5, 8, 9, 12).withIndex()) {
            val target = LatLng(center.latitude + i * 0.002, center.longitude - i * 0.002)
            compose.runOnIdle { map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom.toDouble())) }
            compose.waitUntil(15_000) {
                if (live) {
                    compose.runOnIdle {
                        val view = (compose.activity.window.decorView as ViewGroup).descendants.filterIsInstance<MapView>().single()
                        map.queryRenderedFeatures(android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())).isNotEmpty()
                    }
                } else {
                    compose.runOnIdle {
                        val screen = map.projection.toScreenLocation(target)
                        map.queryRenderedFeatures(screen, "chart").any { it.getStringProperty("tile") == tileAddress(zoom, target) }
                    }
                }
            }
            Log.i("ChartPackTest", "Rendered offline z$zoom at ${target.latitude},${target.longitude}")
        }
    }

    private fun tileAddress(zoom: Int, target: LatLng): String {
        val count = 1 shl zoom
        val x = floor((target.longitude + 180.0) / 360.0 * count).toInt()
        val y = floor((1.0 - asinh(tan(Math.toRadians(target.latitude))) / Math.PI) / 2.0 * count).toInt()
        return "$zoom/$x/$y"
    }

    // Every length-delimited field in this vector tile fits in one protobuf length byte.
    private fun bytesField(number: Int, bytes: ByteArray): ByteArray {
        require(bytes.size < 128)
        return byteArrayOf(((number shl 3) or 2).toByte(), bytes.size.toByte()) + bytes
    }

    private fun vectorTile(path: String): ByteArray {
        val address = path.substringAfter("/tiles/").substringBefore(".pbf").encodeToByteArray()
        // In a 16×16 extent, move to (0,0), draw three edges with zigzag deltas, then close.
        val ring = byteArrayOf(9, 0, 0, 26, 32, 0, 0, 32, 31, 0, 15)
        val tags = bytesField(2, byteArrayOf(0, 0)) // First key and first value.
        val polygon = tags + byteArrayOf(24, 3) + bytesField(4, ring)
        val layer = bytesField(1, "chart".encodeToByteArray()) + bytesField(2, polygon) +
            bytesField(3, "tile".encodeToByteArray()) + bytesField(4, bytesField(1, address)) +
            byteArrayOf(40, 16, 120, 2) // Extent 16, vector tile specification v2.
        return bytesField(3, layer)
    }

    private fun fixture(path: String, base: String): ByteArray = when {
        path.endsWith("/style.json") -> """
            {"version":8,"glyphs":"$base/fonts/{fontstack}/{range}.pbf","sprite":"$base/sprite",
             "sources":{"tiles":{"type":"vector","tiles":["$base/tiles/{z}/{x}/{y}.pbf"]},
                        "labels":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}},
             "layers":[{"id":"back","type":"background"},{"id":"chart","type":"fill","source":"tiles","source-layer":"chart"},
                       {"id":"label","type":"symbol","source":"labels","layout":{"text-font":["Test Font"],"text-field":"Test"}}]}
        """.trimIndent().toByteArray()
        path.contains("/tiles/") -> vectorTile(path)
        path.contains("/sprite") && path.endsWith(".png") -> png(Color.WHITE)
        path.contains("/sprite") && path.endsWith(".json") -> "{}".toByteArray()
        path.contains("/fonts/") -> byteArrayOf()
        else -> error("Unexpected fixture resource $path")
    }

    private fun png(color: Int): ByteArray {
        val tile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        tile.eraseColor(color)
        return ByteArrayOutputStream().use {
            tile.compress(Bitmap.CompressFormat.PNG, 100, it)
            tile.recycle()
            it.toByteArray()
        }
    }
}
