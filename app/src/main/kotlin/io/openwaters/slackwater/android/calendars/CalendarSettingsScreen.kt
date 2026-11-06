package io.openwaters.slackwater.android.calendars

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.*
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
private fun Context.hasCalendarAccess() = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private data class CalendarRow(val id: StationId, val title: String)
private data class Removal(val row: CalendarRow, val count: Int)

@Composable
internal fun StationCalendarButton(current: StationId) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.station_calendars_title)) }
    if (open) Dialog(onDismissRequest = { open = false }) {
        Surface(shape = MaterialTheme.shapes.large) { CalendarSettingsScreen(current) { open = false } }
    }
}

@Composable
private fun CalendarSettingsScreen(current: StationId, close: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { CalendarRepository.get(context) }
    val catalog = remember { BundledStationCatalog(context.assets) }
    val subscriptions by remember { repo.subscriptions.retryAlertReads() }.collectAsState(emptyList())
    val pending by remember { repo.cleanupPending.retryAlertReads() }.collectAsState(false)
    val favorites by remember { UserStateStore.get(context).places }.collectAsState(null)
    val access by remember { ReminderAccessStore.get(context).changes.retryAlertReads() }.collectAsState(ReminderEntitlement.Free)
    val revision by remember { ModelStore.get(context).revision }.collectAsState()
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(60_000) } }
    val ids = (listOf(current) + favorites?.getOrNull()?.favorites.orEmpty() + subscriptions.map { it.stationId }).distinct()
    val rows by produceState(emptyList<CalendarRow>(), ids) {
        value = ids.map { id ->
            val resolution = catalog.resolve(id)
            val station = when (resolution) {
                is StationResolution.Known -> resolution.identity
                is StationResolution.Successor -> resolution.target.identity
                else -> null
            }
            CalendarRow(id, station?.let(::stationCalendarTitle) ?: station?.name ?: id.value)
        }
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableIntStateOf(0) }
    var removal by remember { mutableStateOf<Removal?>(null) }
    var requested by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = 0
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { permissionDenied = true }
            catch (_: Exception) { message = R.string.station_calendar_failed }
            finally { busy = false }
        }
    }
    fun select(row: CalendarRow) = runAction {
        if (subscriptions.any { it.stationId == row.id }) {
            removal = Removal(row, repo.eventCount(row.id))
        } else if (!repo.subscribe(row.id)) message = R.string.station_calendar_failed
    }
    var permissionGranted by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionDenied = !context.hasCalendarAccess()
        permissionGranted = !permissionDenied
        if (permissionDenied) requested = null else repo.foreground()
    }
    LaunchedEffect(requested, permissionGranted, rows) {
        if (!permissionGranted) return@LaunchedEffect
        val row = rows.firstOrNull { it.id.value == requested } ?: return@LaunchedEffect
        requested = null
        permissionGranted = false
        select(row)
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionDenied = !context.hasCalendarAccess()
        if (!permissionDenied) repo.foreground()
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).padding(20.dp)) {
        Text(stringResource(R.string.station_calendars_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.station_calendars_about), Modifier.padding(vertical = 8.dp))
        if (pending) Text(stringResource(R.string.station_calendar_pending))
        if (permissionDenied || (pending && !context.hasCalendarAccess())) {
            Text(stringResource(R.string.station_calendar_permission))
            TextButton(onClick = {
                settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.station_calendar_settings)) }
        }
        if (message != 0) Text(stringResource(message))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(rows, key = { it.id.value }) { row ->
                val sub = subscriptions.firstOrNull { it.stationId == row.id }
                val available by produceState<Boolean?>(null, row.id, revision, sub != null) {
                    value = sub != null || repo.canSubscribe(row.id)
                }
                HorizontalDivider()
                Text(row.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                val status = when {
                    sub?.pendingDeletion == true -> R.string.station_calendar_pending_remove
                    sub?.origin == CalendarOrigin.FREE_DAY && (sub.trialEnd?.isAfter(now) != true || !access.activeAt(now)) -> R.string.station_calendar_stopped
                    sub?.origin == CalendarOrigin.FREE_DAY -> R.string.station_calendar_trial
                    sub != null -> R.string.station_calendar_active
                    available == false -> R.string.station_calendar_offline_needed
                    else -> 0
                }
                if (status != 0) Text(stringResource(status))
                TextButton(enabled = !busy && sub?.pendingDeletion != true && (sub != null || available == true), onClick = {
                    when {
                        sub == null && !access.activeAt(Instant.now()) -> message = R.string.station_calendar_premium
                        !context.hasCalendarAccess() -> { requested = row.id.value; request.launch(permissions) }
                        else -> select(row)
                    }
                }) { Text(stringResource(if (sub != null) R.string.station_calendar_remove else R.string.station_calendar_subscribe)) }
            }
        }
        TextButton(onClick = close) { Text(stringResource(R.string.close)) }
    }
    removal?.let { prompt ->
        AlertDialog(onDismissRequest = { removal = null },
            title = { Text(stringResource(R.string.station_calendar_remove_title, prompt.row.title)) },
            text = { Text(pluralStringResource(R.plurals.station_calendar_remove_count, prompt.count, prompt.count)) },
            confirmButton = {
                TextButton(onClick = {
                    removal = null
                    runAction { repo.unsubscribe(prompt.row.id) }
                }) { Text(stringResource(R.string.station_calendar_remove)) }
            },
            dismissButton = { TextButton(onClick = { removal = null }) { Text(stringResource(R.string.station_calendar_cancel)) } })
    }
}
