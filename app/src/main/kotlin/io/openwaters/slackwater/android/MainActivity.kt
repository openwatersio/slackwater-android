package io.openwaters.slackwater.android

import io.openwaters.slackwater.android.shortcuts.ShortcutKind
import io.openwaters.slackwater.android.shortcuts.ShortcutRequest
import io.openwaters.slackwater.android.shortcuts.ShortcutPublisher
import android.content.Intent
import android.provider.CalendarContract
import io.openwaters.slackwater.android.calendars.CalendarRepository
import io.openwaters.slackwater.android.alerts.AlertDestination
import io.openwaters.slackwater.android.alerts.StationAlerts
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.data.AlertReconciliation
import io.openwaters.slackwater.android.stations.StationsHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var destination by mutableStateOf<AlertDestination?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDestination(intent)
    }

    private fun handleDestination(intent: Intent) {
        destination = AlertDestination.read(intent)
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
                if (showCredits) {
                    val notices = remember { resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } }
                    AlertDialog(
                        onDismissRequest = { showCredits = false },
                        title = { Text(stringResource(R.string.data_credits)) },
                        text = {
                            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                                Text(notices)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showCredits = false }) { Text(stringResource(R.string.close)) }
                        },
                    )
                }
                Surface(Modifier.fillMaxSize()) {
                    // The list stays composed under an alert's destination, so its scroll, search and page survive.
                    Box(if (destination == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics {}) {
                        StationsHome(onCredits = { showCredits = true }, coveredByAlert = destination != null) {
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
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                                key(target) {
                                    StationAlerts(target, target.station) {
                                        setIntent(Intent(this@MainActivity, MainActivity::class.java))
                                        destination = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
