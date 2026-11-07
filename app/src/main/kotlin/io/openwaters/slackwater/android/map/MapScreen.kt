package io.openwaters.slackwater.android.map

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.map.PIN_READING_MIN_ZOOM
import io.openwaters.slackwater.android.core.map.PIN_ZOOM_SLACK
import io.openwaters.slackwater.android.core.map.PinBox
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import io.openwaters.slackwater.android.stations.StationCard
import io.openwaters.slackwater.android.stations.StationListModel
import java.time.Instant
import kotlin.math.abs
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

private data class MapViewport(val bounds: PinBox, val zoom: Double, val center: Coordinate)

private data class PinCamera(val bounds: PinBox, val zoom: Double) {
    fun covers(view: MapViewport): Boolean = bounds.covers(view.bounds) &&
        abs(zoom - view.zoom) < PIN_ZOOM_SLACK &&
        (zoom >= PIN_READING_MIN_ZOOM) == (view.zoom >= PIN_READING_MIN_ZOOM)
}

@Composable
internal fun MapScreen(list: StationListModel, engine: StationMapModel, now: Instant, focusId: String?, focusGeneration: Int,
                       open: (StationId) -> Unit, close: () -> Unit, settings: () -> Unit,
                       showListButton: Boolean = true, styleUrl: String = BASEMAP_STYLE_URL) {
    val context = LocalContext.current
    val catalog = remember { BundledStationCatalog(context.assets) }
    val listState by list.state.collectAsState()
    val prefs by list.readingPreferences.collectAsState()
    val revision by engine.revision.collectAsState()
    val savedFilter = listState?.filter
    var bypassFilter by rememberSaveable(savedFilter) { mutableStateOf(false) }
    val filter = savedFilter.takeUnless { bypassFilter }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val fix = (listState?.slot as? LocationSlot.Located)?.fix?.coordinate
    val anchor = listState?.sections?.anchor?.coordinate
    var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var zoom by rememberSaveable { mutableDoubleStateOf(7.35) }
    var center by remember { mutableStateOf(latitude?.let { lat -> longitude?.let { Coordinate(lat, it) } }) }
    var requestedZoom by remember { mutableDoubleStateOf(zoom) }
    var cameraRequest by remember { mutableIntStateOf(0) }
    var handledFocus by rememberSaveable { mutableIntStateOf(-1) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var pinCamera by remember { mutableStateOf<PinCamera?>(null) }
    var basemapStatus by remember { mutableStateOf(BasemapStatus.Loading) }
    var localOnly by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var listing by rememberSaveable { mutableStateOf(false) }
    var locationError by remember { mutableStateOf(false) }

    fun move(to: Coordinate, targetZoom: Double) {
        center = to
        requestedZoom = targetZoom
        latitude = to.latitude
        longitude = to.longitude
        zoom = targetZoom
        cameraRequest++
    }
    LaunchedEffect(focusId, focusGeneration, anchor, filter, listState != null) {
        if (listState == null) return@LaunchedEffect
        if (handledFocus != focusGeneration && focusId != null) {
            val resolved = catalog.resolve(StationId(focusId)) as? StationResolution.Known
            if (resolved != null) {
                val station = resolved.identity
                if (filter != null && filter != (station.series ?: StationSeries.Tide)) {
                    bypassFilter = true
                }
                selected = station.id.value
                move(station.coordinate, 12.5)
            }
            handledFocus = focusGeneration
        } else if (center == null && anchor != null) move(anchor, 7.35)
    }
    val selectedStation by produceState<StationIdentity?>(null, selected) {
        value = selected?.let { (catalog.resolve(StationId(it)) as? StationResolution.Known)?.identity }
    }
    val resolvedSelection = selectedStation?.takeIf { it.id.value == selected }
    val preview = resolvedSelection?.takeIf { filter == null || (it.series ?: StationSeries.Tide) == filter }
    LaunchedEffect(filter, resolvedSelection) {
        if (resolvedSelection != null && preview == null) selected = null
    }
    var pins by remember { mutableStateOf(emptyList<StationMapPin>()) }
    val view = viewport
    val buildCamera = pinCamera
    LaunchedEffect(buildCamera, filter, selected, revision, prefs, now) {
        if (buildCamera != null) pins = engine.pins(buildCamera.bounds, buildCamera.zoom, filter, selected?.let(::StationId), now, prefs)
    }
    val visiblePins = remember(pins, filter) {
        pins.filter { filter == null || (it.station.series ?: StationSeries.Tide) == filter }
    }
    fun choose(id: StationId?) {
        selected = id?.value
        if (id != null) {
            val station = visiblePins.firstOrNull { it.station.id == id }?.station
            if (station != null) move(station.coordinate, zoom)
        }
    }
    val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    val activity = LocalActivity.current
    var started by remember { mutableLongStateOf(0) }
    var rationale by remember { mutableStateOf(false) }
    var locating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(locating, lifecycle) {
        if (locating) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (list.location.slot() !is LocationSlot.Located) list.refreshLocation()
            val point = (list.location.slot() as? LocationSlot.Located)?.fix?.coordinate
            if (point == null) locationError = true else {
                selected = null
                move(point, 10.5)
            }
            locating = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grant ->
        val granted = grant.values.any { it }
        val after = activity?.let { a -> permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(a, it) } } ?: false
        list.location.promptAnswered(promptRefused(granted, rationale, after, SystemClock.elapsedRealtime() - started))
        if (granted) locating = true else locationError = true
    }
    val density = LocalDensity.current
    var bottom by remember { mutableIntStateOf(8) }
    Box(Modifier.fillMaxSize().testTag("station-map")) {
        if (center != null) NativeStationMap(visiblePins, preview?.id, fix, center, requestedZoom, cameraRequest, reload,
            onViewport = { s, w, n, e, z, c ->
                val next = MapViewport(PinBox(s, w, n, e), z, c)
                viewport = next
                if (pinCamera?.covers(next) != true) pinCamera = PinCamera(next.bounds.padded(), z)
                latitude = c.latitude; longitude = c.longitude; zoom = z
            }, onSelect = ::choose, onBasemapStatus = { basemapStatus = it }, modifier = Modifier.fillMaxSize(),
            bottomMarginDp = bottom + 8, styleUrl = styleUrl, localOnly = localOnly)
        Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp)) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(null to R.string.list_filter_all, StationSeries.Tide to R.string.list_filter_tides,
                        StationSeries.Current to R.string.list_filter_currents).forEach { (choice, label) ->
                        FilterChip(selected = filter == choice, label = { Text(stringResource(label)) },
                            onClick = {
                                if (choice != null && resolvedSelection != null &&
                                    (resolvedSelection.series ?: StationSeries.Tide) != choice) selected = null
                                if (choice == savedFilter) bypassFilter = false
                                list.launchEdit { list.listPreferences.setSeriesFilter(choice) }
                            })
                    }
                }
            }
            Surface(shape = MaterialTheme.shapes.large) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { listing = true }) { Text(stringResource(R.string.map_stations)) }
                    for ((step, label) in listOf(-1 to R.string.map_zoom_out, 1 to R.string.map_zoom_in)) {
                        val description = stringResource(label)
                        IconButton(enabled = view != null, onClick = {
                            view?.let { move(it.center, (it.zoom + step).coerceIn(0.0, 22.0)) }
                        }, modifier = Modifier.semantics { contentDescription = description }) {
                            Text(if (step > 0) "+" else "−")
                        }
                    }
                }
            }
            val statusMessage = when (basemapStatus) {
                BasemapStatus.Slow -> R.string.map_slow
                BasemapStatus.Unavailable -> R.string.map_failure
                BasemapStatus.Retrying -> R.string.map_loading
                BasemapStatus.LocalOnly -> R.string.map_local
                else -> null
            }
            if (statusMessage != null) Surface(shape = MaterialTheme.shapes.medium) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(statusMessage), Modifier.padding(start = 12.dp))
                    if (basemapStatus == BasemapStatus.Retrying) {
                        TextButton(onClick = { localOnly = true }) { Text(stringResource(R.string.map_use_local)) }
                    } else {
                        TextButton(onClick = { localOnly = false; reload++ }) { Text(stringResource(R.string.map_retry)) }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottom = with(density) { it.height.toDp().value.toInt() } }
            .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            preview?.let { station ->
                Surface(tonalElevation = 6.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 480.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (station.kind == StationKind.Restricted) TextButton(onClick = { open(station.id) }) {
                                Column { Text(station.name); Text(stringResource(R.string.detail_unavailable)) }
                            } else key(station.id, revision) {
                                StationCard(station, null, now, prefs, { at, threshold -> list.card(station.id, at, threshold) }, { open(station.id) })
                            }
                        }
                        TextButton(onClick = { selected = null }) { Text(stringResource(R.string.map_close)) }
                    }
                }
            }
            val height = stringResource(if (prefs.heightUnits(LocalConfiguration.current.locales[0]) == HeightUnits.IMPERIAL) R.string.unit_feet else R.string.unit_metres)
            val speed = stringResource(prefs.speedUnit.unitLabel)
            val spoken = stringResource(R.string.map_units_legend, height, speed)
            Surface(onClick = settings, shape = MaterialTheme.shapes.large, tonalElevation = 6.dp,
                modifier = Modifier.heightIn(min = 48.dp).testTag("map-disclaimer").semantics { contentDescription = spoken }) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.map_legend, height, speed), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.map_disclaimer), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ExtendedFloatingActionButton(onClick = {
                    when (list.location.slot()) {
                        LocationSlot.NotDetermined -> {
                            rationale = activity?.let { a -> permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(a, it) } } ?: false
                            started = SystemClock.elapsedRealtime()
                            list.locationPromptShown()
                            permission.launch(permissions)
                        }
                        LocationSlot.Denied -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        else -> locating = true
                    }
                }) { Text(stringResource(R.string.map_locate)) }
                if (showListButton) ExtendedFloatingActionButton(onClick = close) { Text(stringResource(R.string.map_list)) }
            }
        }
    }
    if (listing) AlertDialog(onDismissRequest = { listing = false }, title = { Text(stringResource(R.string.map_stations)) }, text = {
        val rows = remember(visiblePins, view) { visiblePins.filter { view?.bounds?.contains(it.station.latitude, it.station.longitude) == true }
            .sortedBy { distanceKm(it.station.coordinate, requireNotNull(view).center) } }
        if (rows.isEmpty()) Text(stringResource(R.string.map_empty)) else LazyColumn {
            items(rows, key = { it.station.id.value }) { pin ->
                TextButton(onClick = { choose(pin.station.id); listing = false }) { Text(pin.station.name + " · " + pin.station.region) }
            }
        }
    }, confirmButton = { TextButton(onClick = { listing = false }) { Text(stringResource(R.string.close)) } })
    if (locationError) AlertDialog(onDismissRequest = { locationError = false }, text = { Text(stringResource(R.string.map_fix_missing)) },
        confirmButton = { TextButton(onClick = { locationError = false }) { Text(stringResource(R.string.close)) } })
}
