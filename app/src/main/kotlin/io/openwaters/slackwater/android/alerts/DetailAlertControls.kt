package io.openwaters.slackwater.android.alerts

import androidx.compose.ui.window.Dialog
import io.openwaters.slackwater.android.R
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import java.time.Instant
import kotlinx.coroutines.launch

@Composable
internal fun DetailAlertControls(
    now: Instant,
    content: @Composable (List<AlertRule>, (AlertEditorDraft) -> Unit, () -> Unit) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val catalog = remember { BundledStationCatalog(context.assets) }
    val rulesStore = remember { AlertRuleStore.get(context) }
    val scheduler = remember { AlarmScheduler.get(context) }
    val reconcile = remember { AlertReconciliation.get(context) }
    val scope = rememberCoroutineScope()
    val rules by remember { rulesStore.rules.retryAlertReads() }.collectAsState(emptyList())
    val entitlement by remember { ReminderAccessStore.get(context).changes.retryAlertReads() }.collectAsState(ReminderEntitlement.Free)
    val premium = entitlement.activeAt(now)
    var editor by remember { mutableStateOf<AlertEditorDraft?>(null) }
    var showRules by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val promptState = remember { context.getSharedPreferences("reminder_permission", 0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reconcile.foreground()
        message = if (!granted) context.getString(R.string.reminder_notify_blocked)
            else if (!scheduler.access().exactAllowed) context.getString(R.string.reminder_inexact_saved) else null
    }
    content(rules, { editor = it }, { showRules = true })
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
