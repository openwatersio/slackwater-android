package io.openwaters.slackwater.android.detail

import io.openwaters.slackwater.android.sharing.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import java.time.format.TextStyle
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.LocalDate
import java.time.Duration
import io.openwaters.slackwater.android.core.timeline.TimelineMode
import io.openwaters.slackwater.android.core.timeline.TimelineAction

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SchedulePanel(
    station: StationIdentity, moment: Instant, repository: PredictionRepository,
    preferences: ReadingPreferences, modelRevision: Long,
    timeline: DetailTimeline? = null, onChooseDate: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val source = remember(repository) { StationTableSource(context.applicationContext.assets, repository) }
    val zone = remember(station.timezone) { station.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC }
    var offset by rememberSaveable(station.id.value) { mutableIntStateOf(0) }
    val today = moment.atZone(zone).toLocalDate()
    val preview = timeline?.mode == TimelineMode.PREVIEW
    val start = if (preview) today else timeline?.state?.anchor ?: today.plusWeeks(offset.toLong())
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
    val selected = timeline?.state?.selected ?: moment
    val days = remember(data, preview) {
        if (!preview) data.days else data.days.map { day ->
            day.copy(entries = day.entries.filter { it.kind == PrintEventKind.HIGH || it.kind == PrintEventKind.LOW })
        }
    }
    val rows = remember(days) { days.flatMap { it.entries } }
    val nearest = rows.minByOrNull { Duration.between(it.time, selected).abs() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.schedule_title), style = MaterialTheme.typography.titleLarge)
        val end = if (preview) data.covered.lastOrNull()?.end?.minusNanos(1)?.atZone(zone)?.toLocalDate() ?: start
            else start.plusDays(6)
        val year = moment.atZone(zone).year
        val skeleton = if (start.year == year && end.year == year) "MMMd" else "yMMMd"
        val range = remember(start, end, locale, zone, skeleton) {
            android.icu.text.DateIntervalFormat.getInstance(skeleton, locale).apply {
                timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
            }.format(android.icu.util.DateInterval(anchor.toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli()))
        }
        if (preview || onChooseDate == null) Text(range) else TextButton(onClick = onChooseDate, modifier = Modifier.testTag("schedule-week")) { Text("▦  $range  ›") }
        if (!preview) Row {
            TextButton(onClick = { if (timeline == null) offset-- else timeline.dispatch(TimelineAction.PickDay(start.minusWeeks(1))) }) { Text(stringResource(R.string.schedule_earlier)) }
            TextButton(onClick = { if (timeline == null) offset++ else timeline.dispatch(TimelineAction.PickDay(start.plusWeeks(1))) }) { Text(stringResource(R.string.schedule_later)) }
        }
        format.coverage()?.let { Text(it) }
        if (data.unavailableReason != null) Text(stringResource(R.string.schedule_unavailable))
        else for (day in days.filter { it.entries.isNotEmpty() }) {
            val key = day.date.toEpochDay()
            val label = when (day.date) {
                today -> stringResource(R.string.panel_today)
                today.plusDays(1) -> stringResource(R.string.panel_tomorrow)
                today.minusDays(1) -> stringResource(R.string.panel_yesterday)
                else -> null
            }
            val isOpen = expanded == key
            val disclosure = stringResource(if (isOpen) R.string.panel_expanded else R.string.panel_collapsed)
            val dayDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(day.date)
            Row(Modifier.fillMaxWidth().testTag("schedule-day-${day.date}").clickable {
                expanded = if (isOpen) null else key
            }.semantics { stateDescription = disclosure }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label ?: day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.titleSmall)
                    Text(dayDate, style = MaterialTheme.typography.bodySmall)
                }
                if (!isOpen) {
                    Column(horizontalAlignment = Alignment.End) {
                        val rise = day.sunrise?.let(format::time) ?: "—"
                        val set = day.sunset?.let(format::time) ?: "—"
                        val sunInk = MaterialTheme.colorScheme.tertiary
                        val riseLabel = stringResource(R.string.panel_at, stringResource(R.string.panel_rise), rise)
                        val setLabel = stringResource(R.string.panel_at, stringResource(R.string.panel_set), set)
                        Text("☀↑ $rise", Modifier.clearAndSetSemantics { contentDescription = riseLabel }, color = sunInk, style = MaterialTheme.typography.labelSmall)
                        Text("☀↓ $set", Modifier.clearAndSetSemantics { contentDescription = setLabel }, color = sunInk, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(if (isOpen) "⌄" else "›", Modifier.padding(horizontal = 6.dp).clearAndSetSemantics { })
            }
            if (expanded == key) {
                for (entry in day.entries) {
                    val active = entry == nearest
                    ScheduleEventRow(entry, format, active, timeline?.let { { it.jump(entry.time) } })
                }
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

@Composable
internal fun DetailSchedule(
    station: StationIdentity, timeline: DetailTimeline, preferences: ReadingPreferences,
    repository: PredictionRepository, modelRevision: Long, onChooseDate: () -> Unit,
) {
    SchedulePanel(station, timeline.state.now, repository, preferences, modelRevision, timeline, onChooseDate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailDatePicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 1..9999)
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = state.selectedDateMillis != null, onClick = {
            state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
        }) { Text(stringResource(R.string.panel_show)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }) {
        DatePicker(state, title = { Text(stringResource(R.string.panel_choose_date), Modifier.padding(16.dp)) })
    }
}

@Composable
private fun ScheduleEventRow(entry: PrintEntry, format: PrintTableFormat, active: Boolean, jump: (() -> Unit)?) {
    val green = Color(0xFF88B868)
    val ink = Color(0xFF142333)
    val cells = format.weekCells(entry)
    val (glyph, fill) = when (entry.kind) {
        PrintEventKind.HIGH -> "↑" to Color(0xFF2DD4BF)
        PrintEventKind.LOW -> "↓" to Color(0xFFFBBF24)
        PrintEventKind.MAX_FLOOD -> (if (entry.setDegrees == null) "•" else "↑") to Color(0xFF4A9FD8)
        PrintEventKind.MAX_EBB -> (if (entry.setDegrees == null) "•" else "↑") to Color(0xFFE8A33D)
        PrintEventKind.SLACK -> "≈" to Color(0xFFB6C6CE)
        PrintEventKind.SOLAR_ECLIPSE -> "☀" to Color(0xFF6B2A18)
        PrintEventKind.LUNAR_ECLIPSE -> "☾" to Color(0xFF635079)
    }
    val eclipse = entry.peak != null
    Row(Modifier.fillMaxWidth().testTag("schedule-event-${entry.time}").heightIn(min = 48.dp)
        .background(if (active) green.copy(alpha = .13f) else Color.Transparent)
        .selectable(active, enabled = jump != null) { jump?.invoke() },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(40.dp).background(if (active) green else Color.Transparent))
        Text(cells.first(), Modifier.widthIn(min = 65.dp).padding(start = 4.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
        val value = entry.peak?.let(format::time) ?: format.scheduleValue(entry) ?: "—"
        Text(value, Modifier.weight(1f).padding(horizontal = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        val bearing = cells.getOrNull(3)?.takeUnless { it == "—" }
        val kind = when (entry.kind) {
            PrintEventKind.MAX_FLOOD -> stringResource(R.string.panel_flood)
            PrintEventKind.MAX_EBB -> stringResource(R.string.panel_ebb)
            else -> format.name(entry.kind)
        }
        val label = listOfNotNull(bearing, kind).joinToString(" ").uppercase(LocalConfiguration.current.locales[0])
        Surface(color = fill, contentColor = if (eclipse) Color.White else ink, shape = MaterialTheme.shapes.small) {
            Row(Modifier.widthIn(min = 120.dp, max = 148.dp).padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(glyph, Modifier.rotate(entry.setDegrees?.toFloat() ?: 0f).clearAndSetSemantics { })
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
