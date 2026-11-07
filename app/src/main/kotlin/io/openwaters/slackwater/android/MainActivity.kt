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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.openwaters.slackwater.android.detail.DetailRecords
import io.openwaters.slackwater.android.core.station.StationId
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.data.ReadingPreferences
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import io.openwaters.slackwater.android.data.AlertReconciliation
import io.openwaters.slackwater.android.stations.StationsHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private data class DetailDestination(val station: StationId, val event: Instant?)
    private var destination by mutableStateOf<DetailDestination?>(null)
    private val records by lazy { DetailRecords(assets) }
    private var linkLoad: Job? = null
    private var badLink by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDestination(intent)
    }

    private fun handleDestination(intent: Intent) {
        linkLoad?.cancel()
        badLink = false
        if (intent.action == Intent.ACTION_VIEW) {
            val link = intent.data
            linkLoad = lifecycleScope.launch {
                val resolved = link?.let { withContext(Dispatchers.IO) { records.destination(it) } }
                if (resolved == null) badLink = true
                else {
                    setIntent(intent)
                    destination = DetailDestination(resolved.first, resolved.second)
                }
            }
            return
        }
        setIntent(intent)
        destination = AlertDestination.read(intent)?.let { DetailDestination(it.station, it.event) }
        if (intent.action == CalendarContract.ACTION_HANDLE_CUSTOM_EVENT) {
            setResult(if (destination == null) RESULT_CANCELED else RESULT_OK)
        }
    }

    override fun onStart() {
        super.onStart()
        AlertReconciliation.get(this).foreground()
        CalendarRepository.get(this).foreground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDestination(intent)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ShortcutPublisher.publish(this@MainActivity) } }
                var showCredits by remember { mutableStateOf(false) }
                if (badLink) {
                    AlertDialog(onDismissRequest = { badLink = false }, text = { Text(stringResource(R.string.detail_invalid_link)) },
                        confirmButton = { TextButton(onClick = { badLink = false }) { Text(stringResource(R.string.close)) } })
                }
                if (showCredits) {
                    val notices = remember {
                        resources.openRawResource(R.raw.third_party_notices).bufferedReader().use {
                            it.readText().trim().split(Regex("\\n{2,}"))
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { showCredits = false },
                        title = { Text(stringResource(R.string.data_credits)) },
                        text = {
                            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(notices) { paragraph -> Text(paragraph) }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showCredits = false }) { Text(stringResource(R.string.close)) }
                        },
                    )
                }
                var chartSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                var focusMap by remember { mutableStateOf<io.openwaters.slackwater.android.core.station.StationIdentity?>(null) }
                if (chartSettings) io.openwaters.slackwater.android.map.MapSettings { chartSettings = false }
                Surface(Modifier.fillMaxSize()) {
                    // The list stays composed under an alert's destination, so its scroll, search and page survive.
                    Box(if (destination == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics {}) {
                        StationsHome(onCredits = { showCredits = true }, coveredByAlert = destination != null, externalMapFocus = focusMap,
                            consumeFocus = { focusMap = null }, onSettings = { chartSettings = true }) {
                            TextButton(onClick = { chartSettings = true }) { Text(stringResource(R.string.detail_chart_settings)) }
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
