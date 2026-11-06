package io.openwaters.slackwater.android

import java.io.IOException
import io.openwaters.slackwater.database.InvalidStationDatabaseException
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.HarmonicConstituent
import io.openwaters.slackwater.android.core.launchReading
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.openwaters.slackwater.android.data.AlertReconciliation
import io.openwaters.slackwater.android.data.BundledTideDatabase
import io.openwaters.slackwater.android.core.station.StationId

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        AlertReconciliation.get(this).foreground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                var station by remember { mutableStateOf<Pair<String, String>?>(null) }
                var stationUnavailable by remember { mutableStateOf(false) }
                var showCredits by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    station = withContext(Dispatchers.IO) {
                        val record = try {
                            BundledTideDatabase(assets).station(StationId("noaa/9449880"))
                        } catch (_: IOException) {
                            null
                        } catch (_: InvalidStationDatabaseException) {
                            null
                        } ?: return@withContext null
                        val offset = record.chartDatumShift ?: return@withContext null
                        val constituents = record.constituents.map { HarmonicConstituent(it.name, it.amplitude, it.phase) }
                        val reading = launchReading(constituents, offset, record.latitude, record.longitude, Clock.systemUTC())
                        if (reading.heightMetres.isFinite() && reading.sunAltitudeDegrees.isFinite()) {
                            record.name to record.attribution
                        } else null
                    }
                    stationUnavailable = station == null
                }
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
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.shell_message))
                        Text(stringResource(R.string.shell_status))
                        if (stationUnavailable) Text(stringResource(R.string.station_unavailable))
                        station?.let { (name, attribution) ->
                            Text(name)
                            Text(attribution, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.source_notice), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showCredits = true }) { Text(stringResource(R.string.data_and_licenses)) }
                    }
                }
            }
        }
    }
}
