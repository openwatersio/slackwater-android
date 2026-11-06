package io.openwaters.slackwater.android.alerts

import androidx.compose.ui.window.Dialog
import io.openwaters.slackwater.heightAt
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.calendars.StationCalendarButton
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.time.ZoneId
import java.time.ZoneOffset
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.ExtremeKind
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StationAlerts(destination: AlertDestination?, onCloseDestination: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val catalog = remember { BundledStationCatalog(context.assets) }
    val predictions = remember { AndroidPredictionRepository(context) }
    val rulesStore = remember { AlertRuleStore.get(context) }
    val scheduler = remember { AlarmScheduler.get(context) }
    val reconcile = remember { AlertReconciliation.get(context) }
    val scope = rememberCoroutineScope()
    val rules by remember { rulesStore.rules.retryAlertReads() }.collectAsState(emptyList())
    val entitlement by remember { ReminderAccessStore.get(context).changes.retryAlertReads() }.collectAsState(ReminderEntitlement.Free)
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(60_000) } }
    val premium = entitlement.activeAt(now)
    var editor by remember(destination) { mutableStateOf<AlertEditorDraft?>(null) }
    var showRules by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val promptState = remember { context.getSharedPreferences("reminder_permission", 0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reconcile.foreground()
        message = if (!granted) context.getString(R.string.reminder_notify_blocked)
            else if (!scheduler.access().exactAllowed) context.getString(R.string.reminder_inexact_saved) else null
    }
    val id = destination?.station ?: StationId("noaa/9449880")
    val moment = destination?.event ?: now
    val settings by remember { ReadingPreferencesStore.get(context).preferences }.collectAsState(Result.success(ReadingPreferences()))
    val threshold = settings.getOrNull()?.effectiveThresholdKn(id) ?: 0.5
    val revision by remember { ModelStore.get(context).revision }.collectAsState()
    val station by produceState<StationIdentity?>(null, id) {
        value = when (val resolved = catalog.resolve(id)) {
            is StationResolution.Live -> resolved.identity
            is StationResolution.Successor -> (resolved.target as? StationResolution.Live)?.identity
            else -> null
        }
    }
    val detail by produceState<Pair<PredictionResult.Ready, ChartAlertSelection>?>(null, station, moment, threshold, revision) {
        val place = station ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val span = TimeSpan(moment.minusSeconds(if (destination == null) 0 else 43200), moment.plusSeconds(86400))
            val ready = predictions.load(place.id, span, PredictionPurpose.ALERT) as? PredictionResult.Ready
                ?: return@withContext null
            val next = when (val series = ready.series) {
                is PredictionSeries.Tide -> series.events.firstOrNull { it.time >= moment }?.let {
                    AlertTrigger.TideExtreme(it.kind == ExtremeKind.HIGH) to it.time
                }
                is PredictionSeries.Current -> series.events.firstOrNull { it.time >= moment }?.let {
                    val trigger = if (it.kind == CurrentEventKind.SLACK) AlertTrigger.SlackWindowOpens
                        else AlertTrigger.CurrentPeak(it.kind == CurrentEventKind.MAX_FLOOD)
                    trigger to it.time
                }
                is PredictionSeries.Derived -> series.slacks.firstOrNull { it.time >= moment }?.let { AlertTrigger.Slack to it.time }
            } ?: return@withContext null
            val event = if (next.first == AlertTrigger.SlackWindowOpens) {
                val zone = place.timezone?.let(ZoneId::of) ?: ZoneOffset.UTC
                OccurrenceResolver.resolve(AlertRule(stationId = place.id, trigger = next.first), predictions,
                    TimeSpan(moment, span.end), zone, place.latitude, place.longitude, threshold).firstOrNull()?.event
            } else next.second
            ready to ChartAlertSelection(place, ready.capabilities, next.first, next.second, event)
        }
    }
    if (destination != null) {
        val eventReading by produceState<String?>(null, destination, station) {
            val place = station ?: return@produceState
            val event = destination.event
            val result = predictions.load(place.id, TimeSpan(event.minusSeconds(600), event.plusSeconds(600)), PredictionPurpose.DISPLAY)
            val data = when (result) {
                is PredictionResult.Ready -> result
                is PredictionResult.Disjoint -> result.blocks.firstOrNull { event in it.coverage }
                is PredictionResult.Unavailable -> null
            }
            value = when (val series = data?.takeIf { event in it.coverage }?.series) {
                is PredictionSeries.Tide -> series.points.heightAt(event)?.let { "%.2f m".format(it) }
                is PredictionSeries.Current -> series.readingAt(event)?.let { "%.1f kn".format(it.magnitudeKnots) }
                else -> null
            }
        }
        Text(station?.name ?: destination.station.value, style = MaterialTheme.typography.headlineMedium)
        Text(station?.timeLabel(destination.event) ?: destination.event.toString())
        eventReading?.let { Text(it) }
        TextButton(onClick = onCloseDestination) { Text(context.getString(R.string.reminder_back_to_stations)) }
    }
    detail?.let { (ready, selection) ->
        AlertDetailPlot(ready, selection, rules, threshold, predictions, now, onEdit = { editor = it }, center = destination?.event,
            heightUnits = (settings.getOrNull() ?: ReadingPreferences()).heightUnits())
    }
    TextButton(onClick = { showRules = true }) { Text(context.getString(R.string.reminder_my_alerts)) }
    StationCalendarButton(id)
    if (showRules && editor == null) {
        val plan by remember { DeliveryPlanStore.get(context).plan.retryAlertReads() }.collectAsState(null)
        val rows by produceState<List<AlertRow>>(emptyList(), rules, premium, plan) {
            val access = scheduler.access()
            value = rules.map { rule ->
                val identity = (catalog.resolve(rule.stationId) as? StationResolution.Known)?.identity
                    ?: StationIdentity(rule.stationId, rule.stationId.value, "", 0.0, 0.0, StationKind.Retired)
                val through = plan?.scheduledThrough?.get(rule.id)
                val status = when {
                    rule.heightNeedsReview -> context.getString(R.string.reminder_reset_height)
                    !rule.enabled -> context.getString(R.string.reminder_status_off)
                    !premium -> context.getString(R.string.reminder_premium_required)
                    !access.notificationsAllowed -> context.getString(R.string.reminder_notifications_blocked)
                    through == null -> context.getString(R.string.reminder_no_scheduled)
                    !access.exactAllowed -> context.getString(R.string.reminder_inexact)
                    else -> context.getString(R.string.reminder_through, identity.timeLabel(through))
                }
                AlertRow(rule, identity, status)
            }
        }
        Dialog(onDismissRequest = { showRules = false }) {
            Surface { AlertsScreen(rows, onEdit = { editor = it }, onDismiss = { showRules = false }) }
        }
    }
    editor?.let { draft ->
        AlertEditor(draft, premium, onSave = { rule ->
            scope.launch {
                val saved = try {
                    reconcile.save(rule, confirmHeight = draft.rule.heightNeedsReview)
                } catch (_: java.io.IOException) {
                    message = context.getString(R.string.reminder_write_failed)
                    return@launch
                }
                if (!saved) {
                    message = context.getString(R.string.reminder_save_premium)
                    return@launch
                }
                editor = null
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !promptState.getBoolean("requested", false)) {
                    promptState.edit().putBoolean("requested", true).apply()
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val access = scheduler.access()
                    message = when {
                        !access.notificationsAllowed -> context.getString(R.string.reminder_notify_blocked)
                        !access.exactAllowed -> context.getString(R.string.reminder_inexact_saved)
                        else -> null
                    }
                }
            }
        }, onDelete = { scope.launch { rulesStore.remove(it); editor = null } },
            onPremium = { message = context.getString(R.string.reminder_save_premium) }, onDismiss = { editor = null })
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(context.getString(android.R.string.ok)) } })
    }
}
