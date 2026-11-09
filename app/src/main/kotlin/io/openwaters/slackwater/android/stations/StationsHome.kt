package io.openwaters.slackwater.android.stations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.rememberStationDialog
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import io.openwaters.slackwater.android.map.*
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationIdentity
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.tour.tourStation
import java.time.Instant
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Visible cards refresh this often while the app is active. */
private const val CARD_REFRESH_MILLIS = 60_000L

/**
 * Compact windows swap the saved list for a map or station. Expanded windows keep the list beside it. [linkPending]
 * is a link still resolving and [linkOpened] one that opened a station; either keeps the first-run tour from opening
 * its own station (list §11).
 */
@Composable
fun StationsHome(onCredits: () -> Unit, coveredByAlert: Boolean = false, externalMapFocus: StationIdentity? = null,
                 consumeFocus: () -> Unit = {}, linkPending: Boolean = false,
                 linkOpened: Boolean = false,
                 cachedTourCoordinate: (LocationRepository) -> Coordinate? = { it.cachedFix()?.coordinate },
                 model: StationListModel = StationListModel.get(LocalContext.current),
                 footerExtras: @Composable (openSettings: () -> Unit) -> Unit = {}) {
    val context = LocalContext.current
    var settingsOpen by rememberStationDialog(false)
    var replayGeneration by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var mapShowing by rememberSaveable { mutableStateOf(false) }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    var mapGeneration by rememberSaveable { mutableIntStateOf(0) }
    var automatic by rememberSaveable { mutableStateOf(false) }
    var initialChoiceMade by rememberSaveable { mutableStateOf(false) }
    // The station the first-run tour opened, and whether the list has settled if it opens one at all.
    var tourOpened by rememberSaveable { mutableStateOf<String?>(null) }
    var tourDecided by rememberSaveable { mutableStateOf(false) }
    val tour by model.tour.state.collectAsState()
    val listPreferences by model.preferences.collectAsState()
    val catalog by model.catalog.collectAsState()
    val mapModel = remember(context) { lazy { StationMapModel(context) } }
    val current by model.state.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    // The tick loop below starts once, so it reads the latest value through this rather than the first one.
    val covered by rememberUpdatedState(coveredByAlert)
    val pendingLink by rememberUpdatedState(linkPending)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pageState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun closePage() {
        opened?.let { pageState.removeState(it) }
        opened = null
        tourOpened = null
        replayGeneration = 0
    }
    fun open(id: StationId) {
        if (opened != id.value) closePage()
        initialChoiceMade = true
        automatic = false
        searching = false
        opened = id.value
    }
    suspend fun stationForTour(stations: List<StationIdentity>): StationId = withContext(Dispatchers.IO) {
        tourStation(cachedTourCoordinate(model.location), stations)
    }
    suspend fun replayTour(stations: List<StationIdentity>) {
        val target = stationForTour(stations)
        if (covered || pendingLink) return
        if (stations.none { it.id == target }) throw IOException("Tour station is unavailable")
        ReadingPreferencesStore.get(context).changeSkyVisibility(true) { true }
        if (covered || pendingLink) return
        settingsOpen = false
        mapShowing = false
        tourDecided = true
        open(target)
        replayGeneration++
        tourOpened = target.value
        model.tour.replay()
    }
    if (settingsOpen) {
        MapSettings(replay = catalog?.stations?.takeUnless { coveredByAlert || linkPending }?.let { stations -> suspend { replayTour(stations) } },
            close = { settingsOpen = false })
    }
    fun toggleMap() {
        val showMap = !mapShowing || opened != null
        closePage()
        initialChoiceMade = true
        searching = false
        mapShowing = showMap
    }
    fun focus(station: StationIdentity) {
        closePage()
        initialChoiceMade = true
        searching = false
        focused = station.id.value
        mapGeneration++
        mapShowing = true
    }
    LaunchedEffect(externalMapFocus) {
        externalMapFocus?.let { focus(it); consumeFocus() }
    }
    // An unseen tour opens its station on the list's first appearance, from the fix the device already holds; it never
    // waits for a new one. A link that got there first keeps the tour for its own station, and search, the map or
    // a station opened before the list has loaded keep the screen until the list itself shows again.
    val listInView = !searching && !mapShowing && opened == null && !coveredByAlert
    LaunchedEffect(listPreferences != null, catalog != null, linkPending, linkOpened, listInView) {
        val preferences = listPreferences ?: return@LaunchedEffect
        if (tourDecided || linkPending) return@LaunchedEffect
        if (preferences.tourSeen || linkOpened) { tourDecided = true; return@LaunchedEffect }
        val stations = catalog?.stations ?: return@LaunchedEffect
        if (!listInView) return@LaunchedEffect
        val station = stationForTour(stations)
        if (tourDecided || searching || mapShowing || opened != null || coveredByAlert) return@LaunchedEffect
        tourDecided = true
        if (stations.any { it.id == station }) {
            open(station)
            tourOpened = station.value
        }
    }
    // A link that replaced the tour's station takes its place, as it replaces any open page.
    LaunchedEffect(tour.reservedFor) {
        val linked = tour.reservedFor
        if (linked != null && opened != null && opened == tourOpened && opened != linked.value) closePage()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp && maxHeight >= 480.dp
        val wideLayout by rememberUpdatedState(expanded)
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Location is asked for when the list becomes active, then on a tick while locating or once the held fix
                // is halfway to the reuse limit; the clock pauses in the background.
                launch { model.refreshLocation() }
                while (true) {
                    now = Instant.now()
                    delay(CARD_REFRESH_MILLIS)
                    model.tick()
                    val showing = !searching && (wideLayout || opened == null) && !covered
                    if ((showing || mapShowing) && !covered && model.wantsFreshFix(SystemClock.elapsedRealtime())) launch { model.refreshLocation() }
                }
            }
        }
        LaunchedEffect(expanded) {
            if (!expanded && automatic) {
                closePage()
                automatic = false
            }
        }
        val listVisible = expanded || (!mapShowing && opened == null)
        val showing = listVisible && !searching && !coveredByAlert
        // The first launch's location prompt may also rise over the tour's station, which opens in its place.
        val ask = rememberLocationAsk(model, showing || (opened != null && opened == tourOpened && !searching && !coveredByAlert))
        LaunchedEffect(showing, lifecycle) {
            if (showing) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.refreshLocation() }
        }
        LaunchedEffect(expanded, current, coveredByAlert, tourDecided) {
            val sections = current?.sections
            if (expanded && tourDecided && !initialChoiceMade && !coveredByAlert && !mapShowing && !searching && sections != null) {
                val first = sections.heroes.firstOrNull()?.id ?: sections.favorites.firstOrNull { current?.stations?.containsKey(it) == true }
                    ?: sections.nearMe.firstOrNull()?.id
                if (first != null) {
                    opened = first.value
                    automatic = true
                    initialChoiceMade = true
                }
            }
        }
        val searchButton = remember { FocusRequester() }
        val inputMode = LocalInputModeManager.current
        fun dismissSearch() {
            searching = false
            if (listVisible && !coveredByAlert && inputMode.inputMode == InputMode.Keyboard) {
                searchButton.requestFocus()
            }
        }
        BackHandler(!coveredByAlert && (opened != null || mapShowing)) {
            when {
                opened != null -> closePage()
                else -> toggleMap()
            }
        }
        Row(Modifier.fillMaxSize().then(if (searching) Modifier.clearAndSetSemantics {} else Modifier)) {
            if (listVisible) Box(if (expanded) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                pageState.SaveableStateProvider("list") {
                    StationListScreen(model, ::open, onSearch = { initialChoiceMade = true; searching = true }, onCredits = onCredits,
                        now = now, showing = showing, onMap = if (expanded && mapShowing && opened == null) null else ::toggleMap,
                        searchButtonModifier = Modifier.focusRequester(searchButton), onAsk = ask, footerExtras = { footerExtras { settingsOpen = true } })
                }
            }
            if (!coveredByAlert && (expanded || !listVisible)) {
                Box(Modifier.fillMaxSize()) {
                    val station = opened
                    when {
                        station != null -> pageState.SaveableStateProvider(station) {
                            CompositionLocalProvider(LocalOpenStationMap provides ::focus) {
                                key(replayGeneration) { StationPage(model, StationId(station), now, recordVisit = !automatic, onBack = ::closePage) }
                            }
                        }
                        mapShowing -> pageState.SaveableStateProvider("discovery-map") {
                            MapScreen(model, mapModel.value, now, focused, mapGeneration, ::open, ::toggleMap, { settingsOpen = true },
                                showListButton = !expanded)
                        }
                        else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.map_pick), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.map_pick_hint))
                        }
                    }
                }
            }
        }
        if (searching) {
            SearchOverlay(model, now, ::open, ::dismissSearch)
            BackHandler(enabled = !coveredByAlert, onBack = ::dismissSearch)
        }
    }
}
