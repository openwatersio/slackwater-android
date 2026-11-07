package io.openwaters.slackwater.android.map

import android.graphics.RectF
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationId
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.abs

// ChartPacks.swift at the parity pin uses the hosted Fiord style verbatim.
internal const val BASEMAP_STYLE_URL = "https://tiles.openfreemap.org/styles/fiord"

internal enum class BasemapStatus { Loading, Ready, Slow, Unavailable, Retrying, LocalOnly }

@Composable
internal fun NativeStationMap(
    pins: List<StationMapPin>,
    selectedId: StationId?,
    fix: Coordinate?,
    cameraTarget: Coordinate?,
    cameraZoom: Double,
    cameraRequest: Int,
    styleRequest: Int,
    onViewport: (south: Double, west: Double, north: Double, east: Double, zoom: Double, center: Coordinate) -> Unit,
    onSelect: (StationId?) -> Unit,
    onBasemapStatus: (BasemapStatus) -> Unit,
    modifier: Modifier = Modifier,
    bottomMarginDp: Int = 8,
    styleUrl: String = BASEMAP_STYLE_URL,
    localOnly: Boolean = false,
    retryTimeoutMillis: Long = 30_000L,
    fieldsEnabled: Boolean = true,
    fields: CurrentFieldFeatures? = null,
    showCurrentStations: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(context, lifecycle) {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }
    var map by remember(view) { mutableStateOf<MapLibreMap?>(null) }
    var layers by remember(view) { mutableStateOf<StationLayers?>(null) }
    val viewport by rememberUpdatedState(onViewport)
    val select by rememberUpdatedState(onSelect)
    val reportStatus by rememberUpdatedState(onBasemapStatus)
    val density = LocalDensity.current
    val margin = with(density) { bottomMarginDp.dp.roundToPx() }
    val hitRadius = with(density) { 18.dp.toPx() }

    DisposableEffect(view, lifecycle) {
        var active = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        view.getMapAsync { ready ->
            if (active) map = ready
        }
        onDispose {
            active = false
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }

    val ready = map
    if (ready != null) {
        DisposableEffect(ready, fieldsEnabled, showCurrentStations) {
            val idle = MapLibreMap.OnCameraIdleListener {
                val projection = ready.projection
                val bounds = projection.visibleRegion.latLngBounds
                val camera = ready.cameraPosition
                camera.target?.let { target ->
                    val fullWorld = abs(bounds.longitudeEast - bounds.longitudeWest) >= 359.99
                    viewport(bounds.latitudeSouth, if (fullWorld) -180.0 else wrap(bounds.longitudeWest),
                        bounds.latitudeNorth, if (fullWorld) 180.0 else wrap(bounds.longitudeEast),
                        camera.zoom, Coordinate(target.latitude, wrap(target.longitude)))
                }
            }
            val hitLayers = if (fieldsEnabled && showCurrentStations) stationHitLayers
                else stationHitLayers.filterNot { it == "station-vectors" }.toTypedArray()
            val tap = MapLibreMap.OnMapClickListener { at ->
                val xy = ready.projection.toScreenLocation(at)
                val hit = ready.queryRenderedFeatures(RectF(xy.x - hitRadius, xy.y - hitRadius,
                    xy.x + hitRadius, xy.y + hitRadius), *hitLayers)
                    .mapNotNull { feature ->
                        val point = feature.geometry() as? org.maplibre.geojson.Point ?: return@mapNotNull null
                        val screen = ready.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                        val dx = screen.x - xy.x
                        val dy = screen.y - xy.y
                        feature.getStringProperty("id") to dx * dx + dy * dy
                    }.minByOrNull { it.second }
                select(hit?.first?.let(::StationId))
                true
            }
            ready.addOnCameraIdleListener(idle)
            ready.addOnMapClickListener(tap)
            view.post { if (!view.isDestroyed) idle.onCameraIdle() }
            onDispose {
                if (!view.isDestroyed) {
                    ready.removeOnCameraIdleListener(idle)
                    ready.removeOnMapClickListener(tap)
                }
            }
        }
        DisposableEffect(ready, styleRequest, styleUrl, localOnly, fieldsEnabled) {
            var active = true
            var usingLocal = false
            layers = null
            fun useLocal(status: BasemapStatus) {
                if (!active || usingLocal) return
                usingLocal = true
                reportStatus(status)
                ready.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"offline-ground","type":"background","paint":{"background-color":"#152256"}}]}""")) {
                    if (active) layers = StationLayers(it, fieldsEnabled)
                }
            }
            val fallback = Runnable { useLocal(BasemapStatus.Slow) }
            val failure = MapView.OnDidFailLoadingMapListener {
                if (layers == null) useLocal(BasemapStatus.Unavailable)
            }
            view.addOnDidFailLoadingMapListener(failure)
            if (localOnly) {
                useLocal(BasemapStatus.LocalOnly)
            } else {
                val initialLoad = styleRequest == 0
                reportStatus(if (initialLoad) BasemapStatus.Loading else BasemapStatus.Retrying)
                // Give an explicit retry more time, then restore pins even if the network never responds.
                view.postDelayed(fallback, if (initialLoad) 2_000L else retryTimeoutMillis)
                ready.setStyle(styleUrl) {
                    if (active && !usingLocal) {
                        view.removeCallbacks(fallback)
                        layers = StationLayers(it, fieldsEnabled)
                        reportStatus(BasemapStatus.Ready)
                    }
                }
            }
            onDispose {
                active = false
                view.removeCallbacks(fallback)
                view.removeOnDidFailLoadingMapListener(failure)
            }
        }
        LaunchedEffect(ready, cameraRequest) {
            cameraTarget?.let { ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), cameraZoom)) }
        }
        LaunchedEffect(ready, margin) {
            val ui = ready.uiSettings
            ui.setAttributionMargins(ui.attributionMarginLeft, ui.attributionMarginTop, ui.attributionMarginRight, margin)
            ui.setLogoMargins(ui.logoMarginLeft, ui.logoMarginTop, ui.logoMarginRight, margin)
        }
    }
    LaunchedEffect(layers, pins, selectedId) { layers?.update(pins, selectedId) }
    LaunchedEffect(layers, showCurrentStations) { layers?.showCurrentStations(showCurrentStations) }
    LaunchedEffect(layers, fields) { layers?.fields?.update(fields) }
    LaunchedEffect(layers, fix) { layers?.updateFix(fix) }
    AndroidView(factory = { view }, modifier = modifier)
}

private fun wrap(longitude: Double): Double = ((longitude + 180) % 360 + 360) % 360 - 180
