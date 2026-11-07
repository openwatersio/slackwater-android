package io.openwaters.slackwater.android.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.data.*
import kotlinx.coroutines.launch

internal val SpeedUnit.unitLabel get() = when (this) {
    SpeedUnit.KNOTS -> R.string.unit_knots
    SpeedUnit.KILOMETRES_PER_HOUR -> R.string.unit_kilometres_per_hour
    SpeedUnit.METRES_PER_SECOND -> R.string.unit_metres_per_second
}

@Composable
internal fun MapSettings(close: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ReadingPreferencesStore.get(context) }
    val reading by store.preferences.collectAsState(Result.success(ReadingPreferences()))
    val prefs = reading.getOrNull() ?: ReadingPreferences()
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.detail_chart_settings)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.map_tide_units), style = MaterialTheme.typography.titleSmall)
            for (choice in HeightUnits.entries) UnitOption(
                stringResource(if (choice == HeightUnits.IMPERIAL) R.string.spoken_feet else R.string.spoken_metres),
                prefs.heightUnits(LocalConfiguration.current.locales[0]) == choice
            ) { scope.launch { store.setHeightUnits(choice) } }
            Text(stringResource(R.string.map_current_units), style = MaterialTheme.typography.titleSmall)
            for (choice in SpeedUnit.entries) UnitOption(stringResource(choice.unitLabel), prefs.speedUnit == choice) {
                scope.launch { store.setSpeedUnit(choice) }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = prefs.showSky, role = Role.Switch,
                onValueChange = { scope.launch { store.setShowSky(it) } }), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.detail_show_sky), Modifier.weight(1f))
                Switch(prefs.showSky, onCheckedChange = null)
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } })
}

@Composable
private fun UnitOption(text: String, selected: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = choose),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(text, Modifier.padding(start = 12.dp))
    }
}
