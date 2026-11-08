package io.openwaters.slackwater.android.map

import android.icu.text.SimpleDateFormat
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.BuildConfig
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.premium.FreeDayRecord
import io.openwaters.slackwater.android.core.premium.status
import io.openwaters.slackwater.android.core.premium.FreeDayStatus
import java.time.Instant
import java.time.ZoneId
import io.openwaters.slackwater.android.premium.premiumClock
import io.openwaters.slackwater.android.premium.DeviceFreeDayCalendar
import java.util.Date
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PremiumSheet(onClose: () -> Unit) {
    if (!BuildConfig.PREMIUM_ENABLED) return
    val save = rememberSettingsEdit()
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.premium_title), style = MaterialTheme.typography.titleLarge)
            val context = LocalContext.current
            val store = remember(context) { ReminderAccessStore.get(context) }
            var attempt by remember { mutableIntStateOf(0) }
            val record by remember(store, attempt) {
                store.freeDayRecord.map { Result.success(it) }.catch { emit(Result.failure(it)) }
            }.collectAsState(null)
            if (record?.isFailure == true) {
                Text(stringResource(R.string.chart_storage))
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.table_sheet_retry)) }
            }
            record?.getOrNull()?.let {
                FreeDayControl(it, attempt) { save {
                    if (!store.startFreeDay(Instant.now(), ZoneId.systemDefault())) attempt++
                } }
            }
            Text(stringResource(R.string.premium_support), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
        }
    }
}

@Composable
internal fun FreeDayControl(record: FreeDayRecord, claimAttempt: Int = 0, start: () -> Unit) {
    val context = LocalContext.current
    val now = premiumClock(record, claimAttempt, LocalConfiguration.current) { instant ->
        val zone = ZoneId.systemDefault()
        val calendar = DeviceFreeDayCalendar()
        val statusEnd = when (val status = record.status(instant, zone, calendar)) {
            is FreeDayStatus.Active -> status.ends
            is FreeDayStatus.Used -> status.next
            else -> null
        }
        val futureStart = record.started?.takeIf { !record.purchased && it > instant }
        val claimedMonthStart = futureStart?.let { calendar.monthStart(it, zone) }?.takeIf { it > instant }
        listOfNotNull(statusEnd, futureStart, claimedMonthStart).minOrNull()
    }
    val zone = ZoneId.systemDefault()
    val status = record.status(now, zone, DeviceFreeDayCalendar())
    when (status) {
        FreeDayStatus.Unavailable -> Text(stringResource(R.string.premium_day_unavailable))
        FreeDayStatus.Available -> OutlinedButton(onClick = start) { Text(stringResource(R.string.premium_try_free_day)) }
        FreeDayStatus.Purchased -> Text(stringResource(R.string.premium_purchased))
        is FreeDayStatus.Active -> Text(stringResource(R.string.premium_free_day_until,
            localizedDate(status.ends, zone, if (DateFormat.is24HourFormat(context)) "EEEHm" else "EEEhm")))
        is FreeDayStatus.Used -> Text(stringResource(R.string.premium_next_free_day, localizedDate(status.next, zone, "MMMMd")))
    }
}

@Composable
private fun localizedDate(instant: Instant, zone: ZoneId, skeleton: String): String {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).apply {
        calendar = DeviceFreeDayCalendar().calendar(zone)
    }.format(Date.from(instant))
}
