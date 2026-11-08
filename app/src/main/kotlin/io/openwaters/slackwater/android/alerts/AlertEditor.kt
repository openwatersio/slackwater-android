package io.openwaters.slackwater.android.alerts

import io.openwaters.slackwater.android.StationDialogContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import io.openwaters.slackwater.android.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.data.AndroidPredictionRepository
import io.openwaters.slackwater.android.data.ModelStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay

@Composable
private fun leadOptions() = linkedMapOf(0L to stringResource(R.string.alert_on_time), 900L to stringResource(R.string.alert_15min),
    1800L to stringResource(R.string.alert_30min), 3600L to stringResource(R.string.alert_1hour), 10800L to stringResource(R.string.alert_3hours), 86400L to stringResource(R.string.alert_1day))

@Composable
private fun <T> Choice(label: String, value: T, options: Map<T, String>, onChoose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            TextButton(onClick = { open = true }) { Text(options.getValue(value)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (key, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { onChoose(key); open = false })
                }
            }
        }
    }
}

@Composable
fun AlertEditor(initial: AlertEditorDraft, premium: Boolean, onSave: (AlertRule) -> Unit,
                onDelete: (UUID) -> Unit, onPremium: () -> Unit, onDismiss: () -> Unit,
                models: ModelStore = ModelStore.get(LocalContext.current)) = StationDialogContent {
    val original = initial.rule
    var repeating by rememberSaveable(original) { mutableStateOf(original.once == null) }
    var lead by rememberSaveable(original) { mutableLongStateOf(original.leadSeconds) }
    var sun by rememberSaveable(original) { mutableStateOf(original.sun) }
    var enabled by rememberSaveable(original) { mutableStateOf(original.enabled) }
    val crossing = original.trigger as? AlertTrigger.TideCrossing
    var height by rememberSaveable(original) { mutableStateOf(if (original.heightNeedsReview) "" else crossing?.heightM?.toString().orEmpty()) }
    val level = height.toDoubleOrNull()?.takeIf { it.isFinite() }
    val trigger = if (crossing != null && level != null) crossing.copy(heightM = level) else original.trigger
    val bound = if (repeating) null else initial.boundMoment
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(bound, lead) {
        now = Instant.now()
        for (edge in listOfNotNull(bound?.minusSeconds(lead), bound)) {
            if (edge.isAfter(now)) delay(java.time.Duration.between(now, edge).toMillis() + 1)
            now = Instant.now()
        }
    }
    val past = bound != null && bound <= now
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.existing) stringResource(R.string.alert_edit) else stringResource(R.string.alert_set_action)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${initial.station.name} · ${trigger.label()}")
                if (initial.station.kind == StationKind.CanadianCurrent) {
                    val context = LocalContext.current
                    val predictions = remember(context, models) { AndroidPredictionRepository(context, models) }
                    val minutes by produceState<Int?>(null, predictions, initial.station.id) {
                        models.revision.collect {
                            val at = Instant.now()
                            val result = predictions.load(initial.station.id, TimeSpan(at, at), PredictionPurpose.ALERT)
                            value = (result as? PredictionResult.Ready)?.slackErrorBoundMinutes
                        }
                    }
                    minutes?.let { Text(stringResource(R.string.detail_bound, initial.station.name, it)) }
                }
                if (crossing != null) {
                    OutlinedTextField(value = height, onValueChange = { height = it }, singleLine = true,
                        label = { Text(if (original.heightNeedsReview) stringResource(R.string.alert_review_height) else stringResource(R.string.alert_height)) })
                }
                val every = stringResource(R.string.alert_every, trigger.label())
                if (initial.boundMoment != null) {
                    Choice(stringResource(R.string.alert_repeat), repeating, mapOf(false to stringResource(R.string.alert_once), true to every)) { repeating = it }
                } else Text(every)
                bound?.let { Text(initial.station.timeLabel(it)) }
                val leadOptions = leadOptions()
                val leads = if (lead in leadOptions) leadOptions else leadOptions + (lead to stringResource(R.string.alert_seconds, lead))
                Choice(stringResource(R.string.alert_remind), lead, leads) { lead = it }
                Choice(stringResource(R.string.alert_sun), sun, mapOf(null to stringResource(R.string.alert_any_time), SunWindow.DAYLIGHT to stringResource(R.string.alert_daylight),
                    SunWindow.GOLDEN_HOUR to stringResource(R.string.alert_golden), SunWindow.BLUE_HOUR to stringResource(R.string.alert_blue))) { sun = it }
                if (past) Text(stringResource(R.string.alert_past))
                else if (bound != null && bound.minusSeconds(lead) < now) Text(stringResource(R.string.alert_overdue))
                if (initial.existing) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.alert_switch), Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    TextButton(onClick = { onDelete(original.id) }) { Text(stringResource(R.string.alert_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !past && (crossing == null || level != null), onClick = {
                if (bound != null && bound <= Instant.now()) now = Instant.now()
                else if (!premium) onPremium()
                else onSave(original.copy(trigger = trigger, once = bound, leadSeconds = lead, sun = sun,
                    enabled = enabled, heightNeedsReview = false))
            }) { Text(stringResource(R.string.alert_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.alert_cancel)) } },
    )
}
