package io.openwaters.slackwater.android.sharing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.alerts.activeAt
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.data.ReadingPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun StationSchedule(
    station: StationIdentity, moment: Instant, repository: PredictionRepository,
    preferences: ReadingPreferences, modelRevision: Long,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val source = remember(repository) { StationTableSource(context.applicationContext.assets, repository) }
    val zone = remember(station.timezone) { station.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC }
    var offset by rememberSaveable(station.id.value) { mutableIntStateOf(0) }
    val start = moment.atZone(zone).toLocalDate().plusWeeks(offset.toLong())
    val anchor = start.atStartOfDay(zone).toInstant()
    var failed by remember(station.id, anchor, modelRevision) { mutableStateOf(false) }
    val week by key(station.id, anchor, modelRevision) {
        produceState<PrintableTable?>(null) {
            try { value = source.load(station, PrintSpan.WEEK, anchor, Instant.now()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    if (failed) {
        Text(stringResource(R.string.schedule_unavailable))
        if (offset != 0) TextButton(onClick = { offset = 0 }) { Text(stringResource(R.string.schedule_reset)) }
    }
    val data = week ?: return
    if (data.unavailableReason != null && data.unavailableReason != PredictionResult.Reason.OUTSIDE_COVERAGE &&
        offset == 0) return
    var expanded by rememberSaveable(station.id.value, start.toEpochDay()) { mutableStateOf<Long?>(start.toEpochDay()) }
    val scope = rememberCoroutineScope()
    var sheet by rememberSaveable(station.id.value) { mutableStateOf(false) }
    var support by rememberSaveable { mutableStateOf(false) }
    val format = remember(data, preferences, locale) {
        PrintTableFormat(context, data, preferences, locale)
    }
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.schedule_title), style = MaterialTheme.typography.titleLarge)
        Text("${date.format(start)} – ${date.format(start.plusDays(6))}")
        Row {
            TextButton(onClick = { offset-- }) { Text(stringResource(R.string.schedule_earlier)) }
            TextButton(onClick = { offset++ }) { Text(stringResource(R.string.schedule_later)) }
        }
        if (data.unavailableReason != null) Text(stringResource(R.string.schedule_unavailable))
        else for (day in data.days.filter { it.entries.isNotEmpty() }) {
            val key = day.date.toEpochDay()
            Text(format.dayLabel(day), Modifier.fillMaxWidth().clickable {
                expanded = if (expanded == key) null else key
            }.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            if (expanded == key) {
                for (entry in day.entries) Text(format.scheduleRow(entry))
            }
        }
        HorizontalDivider()
        TextButton(onClick = {
            scope.launch {
                val allowed = try {
                    ReminderAccessStore.get(context).current().activeAt(Instant.now())
                } catch (_: java.io.IOException) {
                    false
                }
                if (allowed) sheet = true else support = true
            }
        }, modifier = Modifier.testTag("printable-tables-row")) { Text(stringResource(R.string.schedule_table_action)) }
    }
    if (support) AlertDialog(onDismissRequest = { support = false },
        title = { Text(stringResource(R.string.schedule_support_title)) },
        text = { Text(stringResource(R.string.schedule_premium_message)) },
        confirmButton = { TextButton(onClick = { support = false }) { Text(stringResource(R.string.close)) } })
    if (sheet) key(anchor, modelRevision, preferences) {
        PrintableTableSheet(station.name, load = { period ->
            source.load(station, period, anchor, Instant.now())
        }, onDismiss = { sheet = false })
    }
}
