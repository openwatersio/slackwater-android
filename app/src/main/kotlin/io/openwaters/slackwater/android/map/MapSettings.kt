package io.openwaters.slackwater.android.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.BuildConfig
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.data.*
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.station.StationId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.io.IOException
import java.time.Instant
import io.openwaters.slackwater.android.core.planning.allows
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
    val save = rememberSettingsEdit()
    var premiumOpen by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.settings_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.map_tide_units), style = MaterialTheme.typography.titleSmall)
            for (choice in HeightUnits.entries) UnitOption(
                stringResource(if (choice == HeightUnits.IMPERIAL) R.string.spoken_feet else R.string.spoken_metres),
                prefs.heightUnits(LocalConfiguration.current.locales[0]) == choice
            ) { save { store.setHeightUnits(choice) } }
            Text(stringResource(R.string.map_current_units), style = MaterialTheme.typography.titleSmall)
            for (choice in SpeedUnit.entries) UnitOption(stringResource(choice.unitLabel), prefs.speedUnit == choice) {
                save { store.setSpeedUnit(choice) }
            }
            Text(stringResource(R.string.settings_set_format), style = MaterialTheme.typography.titleSmall)
            for (choice in SetFormat.entries) UnitOption(
                stringResource(if (choice == SetFormat.COMPASS) R.string.settings_compass else R.string.settings_degrees),
                prefs.setFormat == choice
            ) { save { store.setSetFormat(choice) } }
            ComfortCurrentControl(prefs)
            SlackWindowPreview(prefs.slackWindowKnots)
            Text(stringResource(R.string.settings_comfort_help), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = prefs.showSky, role = Role.Switch,
                onValueChange = { show -> save {
                    val changed = store.changeSkyVisibility(show) {
                        ReminderAccessStore.get(context).current().allows(Instant.now())
                    }
                    if (!changed) premiumOpen = true
                } }), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.detail_show_sky), Modifier.weight(1f))
                val premiumLabel = stringResource(R.string.settings_premium)
                Text("✦", Modifier.padding(horizontal = 6.dp).clearAndSetSemantics { contentDescription = premiumLabel })
                Switch(prefs.showSky, onCheckedChange = null)
            }
            if (BuildConfig.PREMIUM_ENABLED) TextButton(onClick = { premiumOpen = true }) { Text(stringResource(R.string.settings_premium)) }
            AboutSettingsLinks()
        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } })
    if (premiumOpen) {
        if (BuildConfig.PREMIUM_ENABLED) PremiumSheet(onClose = { premiumOpen = false })
        else AlertDialog(onDismissRequest = { premiumOpen = false },
            text = { Text(stringResource(R.string.reminder_premium_required)) },
            confirmButton = { TextButton(onClick = { premiumOpen = false }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
internal fun ComfortCurrentControl(prefs: ReadingPreferences, station: StationId? = null) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val store = remember(context) { ReadingPreferencesStore.get(context) }
    val save = rememberSettingsEdit()
    val knots = station?.let(prefs::effectiveThresholdKn) ?: prefs.slackWindowKnots
    val enabled = station == null || !prefs.slackWindowOff
    Text(stringResource(if (station == null) R.string.settings_comfort_current else R.string.settings_station_comfort),
        style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val display = if (knots == 0.0) stringResource(R.string.reminder_status_off)
            else String.format(locale, "%.1f %s", knots, stringResource(R.string.unit_knots))
        Text(display, Modifier.weight(1f))
        for (increase in listOf(false, true)) {
            val label = stringResource(if (increase) R.string.settings_increase else R.string.settings_decrease)
            IconButton(onClick = { save { store.stepComfortCurrent(increase, station) } },
                enabled = enabled && (if (increase) knots < 10.0 else knots > 0.0),
                modifier = Modifier.semantics { contentDescription = label }) { Text(if (increase) "+" else "−") }
        }
    }
    if (station != null && prefs.slackWindowOff) {
        Text(stringResource(R.string.query_off), style = MaterialTheme.typography.bodySmall)
    }
    if (station != null && station in prefs.stationDeltasKn) TextButton(onClick = {
        save { store.setStationThresholdDeltaKn(station, null) }
    }) { Text(stringResource(R.string.settings_use_global)) }
}

@Composable
internal fun rememberSettingsEdit(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    if (failed) AlertDialog(onDismissRequest = { failed = false },
        text = { Text(stringResource(R.string.settings_save_error)) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.close)) } })
    return { edit ->
        scope.launch {
            try { edit() } catch (error: IOException) {
                android.util.Log.w("Slackwater", "Could not apply reading preference", error)
                failed = true
            }
        }
    }
}

@Composable
private fun UnitOption(text: String, selected: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = choose),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(text, Modifier.padding(start = 12.dp))
    }
}
