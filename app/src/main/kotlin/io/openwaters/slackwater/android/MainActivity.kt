package io.openwaters.slackwater.android

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
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                var stationName by remember { mutableStateOf<String?>(null) }
                var showCredits by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    stationName = withContext(Dispatchers.Default) {
                        val record = assets.open("launch-station.json").bufferedReader().use { JSONObject(it.readText()) }
                        val items = record.getJSONArray("constituents")
                        val constituents = (0 until items.length()).map { i ->
                            val c = items.getJSONObject(i)
                            HarmonicConstituent(c.getString("name"), c.getDouble("amplitude"), c.getDouble("phase"))
                        }
                        val reading = launchReading(constituents, record.getDouble("datumOffset"), record.getDouble("latitude"), record.getDouble("longitude"), Clock.systemUTC())
                        check(reading.heightMetres.isFinite() && reading.sunAltitudeDegrees.isFinite())
                        record.getString("name")
                    }
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
                        stationName?.let { Text(it) }
                        Text(stringResource(R.string.source_notice), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showCredits = true }) { Text(stringResource(R.string.data_and_licenses)) }
                    }
                }
            }
        }
    }
}
