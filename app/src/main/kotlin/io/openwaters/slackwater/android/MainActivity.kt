package io.openwaters.slackwater.android

import io.openwaters.slackwater.android.shortcuts.ShortcutKind
import io.openwaters.slackwater.android.shortcuts.ShortcutRequest
import io.openwaters.slackwater.android.shortcuts.ShortcutPublisher
import android.content.Intent
import android.provider.CalendarContract
import io.openwaters.slackwater.android.calendars.CalendarRepository
import io.openwaters.slackwater.android.alerts.AlertDestination
import io.openwaters.slackwater.android.stations.StationPage
import io.openwaters.slackwater.android.stations.StationListModel
import java.time.Instant
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.*
import io.openwaters.slackwater.android.data.OnlineGateDownloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.openwaters.slackwater.android.detail.DetailRecords
import io.openwaters.slackwater.android.core.station.StationId
import android.os.Bundle
import io.openwaters.slackwater.android.map.DataCreditsDialog
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import io.openwaters.slackwater.android.data.ReadingPreferences
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import io.openwaters.slackwater.android.data.TidePreviewDownloads
import io.openwaters.slackwater.android.data.AlertReconciliation
import io.openwaters.slackwater.android.stations.StationsHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    internal companion object {
        // Instrumented UI tests exercise real native downloads only against their bounded test regions.
        internal var automaticChartDownloads = true
    }

    private data class DetailDestination(val station: StationId, val event: Instant?)
    private var destination by mutableStateOf<DetailDestination?>(null)
    private val records by lazy { DetailRecords(assets) }
    private var linkLoad: Job? = null
    private var badLink by mutableStateOf(false)
    // A link still resolving, and whether one has opened a station: either keeps the first-run tour off its own station.
    internal var linkPending by mutableStateOf(false)
        private set
    private var linkOpened by mutableStateOf(false)
    private var linkGeneration = 0
    private var navigationEpoch = 0

    private fun opened(station: StationId, event: Instant?) {
        destination = DetailDestination(station, event)
        linkOpened = true
        StationListModel.get(this).tour.handOff(station)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDestination(intent)
    }

    private fun handleDestination(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val link = intent.data
            linkPending = true
            val generation = ++linkGeneration
            val epoch = navigationEpoch
            val prior = linkLoad
            linkLoad = lifecycleScope.launch {
                try {
                    prior?.join()
                    if (epoch != navigationEpoch) return@launch
                    val resolved = link?.let { withContext(Dispatchers.IO) { records.destination(it) } }
                    if (epoch != navigationEpoch) return@launch
                    if (resolved == null) {
                        if (link?.let { io.openwaters.slackwater.android.detail.internalStationId(it) } == null) badLink = true
                    } else {
                        badLink = false
                        setIntent(intent)
                        opened(resolved.first, resolved.second)
                    }
                } finally {
                    // A newer link may be resolving; only the latest clears the flag.
                    if (generation == linkGeneration) linkPending = false
                }
            }
            return
        }
        navigationEpoch++
        linkLoad?.cancel()
        badLink = false
        linkGeneration++
        linkPending = false
        setIntent(intent)
        val alert = AlertDestination.read(intent)
        if (alert != null) opened(alert.station, alert.event) else destination = null
        if (intent.action == CalendarContract.ACTION_HANDLE_CUSTOM_EVENT) {
            setResult(if (destination == null) RESULT_CANCELED else RESULT_OK)
        }
    }

    override fun onStart() {
        super.onStart()
        TidePreviewDownloads.get(applicationContext).activityStarted()
        AlertReconciliation.get(this).foreground()
        CalendarRepository.get(this).foreground()
    }

    override fun onStop() {
        TidePreviewDownloads.get(applicationContext).activityStopped()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDestination(intent)
        val chartPacks by lazy { io.openwaters.slackwater.android.map.ChartPackManager.get(applicationContext) }
        if (automaticChartDownloads) lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                io.openwaters.slackwater.android.map.chartCoverage(applicationContext).collect { coverage ->
                    chartPacks.setDesired(coverage.specs, coverage.pendingFix, coverage.incompleteStations)
                }
            }
        }
        if (automaticChartDownloads) lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                val downloads = OnlineGateDownloads.get(applicationContext)
                val model = StationListModel.get(applicationContext)
                val candidates = model.state.filterNotNull().map { list ->
                    with(list.sections) { favorites + heroes.map { it.identity.id } + nearMe.map { it.identity.id } + recents }
                }.distinctUntilChanged()
                val wanted = combine(candidates, model.places.downloads.state, downloads.online) { ids, intent, online ->
                    val desired = intent.getOrNull()
                    if (!online || desired == null) emptySet() else (ids - desired.cancelled + desired.requested).toSet()
                }
                downloads.prefetchWhileStarted(wanted)
            }
        }
        enableEdgeToEdge()
        setContent {
            var showDownloads by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            CompositionLocalProvider(io.openwaters.slackwater.android.detail.LocalOpenDownloads provides { showDownloads = true }) {
            MaterialTheme {
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ShortcutPublisher.publish(this@MainActivity) } }
                if (showDownloads) io.openwaters.slackwater.android.map.ChartDownloads(chartPacks) { showDownloads = false }
                var showCredits by remember { mutableStateOf(false) }
                if (badLink) {
                    AlertDialog(onDismissRequest = { badLink = false }, text = { Text(stringResource(R.string.detail_invalid_link)) },
                        confirmButton = { TextButton(onClick = { badLink = false }) { Text(stringResource(R.string.close)) } })
                }
                if (showCredits) DataCreditsDialog { showCredits = false }
                var chartSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                var focusMap by remember { mutableStateOf<io.openwaters.slackwater.android.core.station.StationIdentity?>(null) }
                if (chartSettings) io.openwaters.slackwater.android.map.MapSettings { chartSettings = false }
                Surface(Modifier.fillMaxSize()) {
                    // The list stays composed under an alert's destination, so its scroll, search and page survive.
                    Box(if (destination == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics {}) {
                        StationsHome(onCredits = { showCredits = true }, coveredByAlert = destination != null, externalMapFocus = focusMap,
                            consumeFocus = { focusMap = null }, onSettings = { chartSettings = true },
                            linkPending = linkPending, linkOpened = linkOpened) {
                            TextButton(onClick = { showDownloads = true }) { Text(stringResource(R.string.chart_downloads)) }
                            TextButton(onClick = { chartSettings = true }) { Text(stringResource(R.string.settings_title)) }
                            var showQueries by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { showQueries = true }) { Text(stringResource(R.string.prediction_shortcuts)) }
                                DropdownMenu(expanded = showQueries, onDismissRequest = { showQueries = false }) {
                                    ShortcutKind.entries.forEach { kind ->
                                        DropdownMenuItem(text = { Text(stringResource(kind.label)) }, onClick = {
                                            showQueries = false
                                            startActivity(ShortcutPublisher.intent(this@MainActivity, ShortcutRequest(kind)))
                                        })
                                    }
                                }
                            }
                        }
                    }
                    destination?.let { target ->
                        key(target) {
                            val back = {
                                setIntent(Intent(this@MainActivity, MainActivity::class.java))
                                destination = null
                            }
                            androidx.activity.compose.BackHandler(onBack = back)
                            CompositionLocalProvider(io.openwaters.slackwater.android.map.LocalOpenStationMap provides { station ->
                                back()
                                focusMap = station
                            }) {
                            StationPage(remember { StationListModel.get(this@MainActivity) }, target.station,
                                remember { Instant.now() }, target.event, onBack = back)
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
