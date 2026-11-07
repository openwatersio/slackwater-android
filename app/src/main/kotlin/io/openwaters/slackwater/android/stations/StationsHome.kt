package io.openwaters.slackwater.android.stations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.map.*
import io.openwaters.slackwater.android.core.station.StationIdentity
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Visible cards refresh this often while the app is active. */
private const val CARD_REFRESH_MILLIS = 60_000L

/** Compact windows swap the saved list for a map or station. Expanded windows keep the list beside it. */
@Composable
fun StationsHome(onCredits: () -> Unit, coveredByAlert: Boolean = false, externalMapFocus: StationIdentity? = null,
                 consumeFocus: () -> Unit = {}, onSettings: () -> Unit = {}, footerExtras: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val model = remember { StationListModel.get(context) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var mapShowing by rememberSaveable { mutableStateOf(false) }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    var mapGeneration by rememberSaveable { mutableIntStateOf(0) }
    var automatic by rememberSaveable { mutableStateOf(false) }
    var initialChoiceMade by rememberSaveable { mutableStateOf(false) }
    val mapModel = remember(context) { lazy { StationMapModel(context) } }
    val current by model.state.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    // The tick loop below starts once, so it reads the latest value through this rather than the first one.
    val covered by rememberUpdatedState(coveredByAlert)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pageState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun closePage() {
        opened?.let { pageState.removeState(it) }
        opened = null
    }
    fun open(id: StationId) {
        if (opened != id.value) closePage()
        initialChoiceMade = true
        automatic = false
        searching = false
        opened = id.value
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
        LaunchedEffect(showing, lifecycle) {
            if (showing) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.refreshLocation() }
        }
        LaunchedEffect(expanded, current, coveredByAlert) {
            val sections = current?.sections
            if (expanded && !initialChoiceMade && !coveredByAlert && !mapShowing && !searching && sections != null) {
                val first = sections.heroes.firstOrNull()?.id ?: sections.favorites.firstOrNull { current?.stations?.containsKey(it) == true }
                    ?: sections.nearMe.firstOrNull()?.id
                if (first != null) {
                    opened = first.value
                    automatic = true
                    initialChoiceMade = true
                }
            }
        }
        BackHandler(!coveredByAlert && (searching || opened != null || mapShowing)) {
            when {
                searching -> searching = false
                opened != null -> closePage()
                else -> toggleMap()
            }
        }
        Row(Modifier.fillMaxSize().then(if (searching) Modifier.clearAndSetSemantics {} else Modifier)) {
            if (listVisible) Box(if (expanded) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                pageState.SaveableStateProvider("list") {
                    StationListScreen(model, ::open, onSearch = { initialChoiceMade = true; searching = true }, onCredits = onCredits,
                        now = now, showing = showing, onMap = if (expanded && mapShowing && opened == null) null else ::toggleMap,
                        footerExtras = footerExtras)
                }
            }
            if (!coveredByAlert && (expanded || !listVisible)) {
                Box(Modifier.fillMaxSize()) {
                    val station = opened
                    when {
                        station != null -> pageState.SaveableStateProvider(station) {
                            CompositionLocalProvider(LocalOpenStationMap provides ::focus) {
                                StationPage(model, StationId(station), now, recordVisit = !automatic, onBack = ::closePage)
                            }
                        }
                        mapShowing -> pageState.SaveableStateProvider("discovery-map") {
                            MapScreen(model, mapModel.value, now, focused, mapGeneration, ::open, ::toggleMap, onSettings,
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
        if (searching) SearchOverlay(model, now, ::open) { searching = false }
    }
}
