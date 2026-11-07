package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.timeline.TimelineWindow
import io.openwaters.slackwater.android.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The last local anchor for which the entire strip fits inside this one saved block. */
internal fun coveredThrough(spans: List<TimeSpan>, window: TimelineWindow, zone: ZoneId): LocalDate? {
    val block = spans.firstOrNull { window.start in it && window.end in it } ?: return null
    return block.end.minus(TimelineWindow.forward).atZone(zone).toLocalDate()
}

internal val LocalOpenDownloads = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun onlineCoverage(id: StationId, revision: Long): List<TimeSpan> {
    val context = LocalContext.current
    val store = remember(context) { OnlineWindowStore(ModelStore.get(context)) }
    val spans by produceState(emptyList<TimeSpan>(), id, revision) {
        value = withContext(Dispatchers.IO) { store.coverage(id) }
    }
    return spans
}

internal fun savedThrough(spans: List<TimeSpan>, today: LocalDate, zone: ZoneId): LocalDate? {
    return coveredThrough(spans, TimelineWindow.fixed(today, zone), zone)
        ?: spans.lastOrNull { it.start <= today.atStartOfDay(zone).toInstant() }
            ?.end?.minus(TimelineWindow.forward)?.atZone(zone)?.toLocalDate()
}

@Composable
internal fun OnlineValidity(through: LocalDate?, today: LocalDate) {
    if (through == null) return
    val locale = LocalConfiguration.current.locales[0]
    val monthDay = remember(locale) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    }
    Text(if (through < today) stringResource(R.string.gate_expired)
        else stringResource(R.string.detail_network_missing, through.format(monthDay)))
}

@Composable
internal fun OnlineGateValidity(downloads: OnlineGateDownloads, id: StationId, today: LocalDate, zone: ZoneId, revision: Long) {
    val online by downloads.online.collectAsState()
    val states by downloads.weekStates.collectAsState()
    val scope = rememberCoroutineScope()
    val open = LocalOpenDownloads.current
    val state = states[id to today]
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.gate_offline_available), style = MaterialTheme.typography.titleMedium)
            OnlineValidity(savedThrough(onlineCoverage(id, revision), today, zone), today)
            Text(stringResource(R.string.gate_offline_expectation))
            TextButton(enabled = online && (state != OnlineDownloadState.DOWNLOADING || open != null), onClick = {
                if (state == OnlineDownloadState.DOWNLOADING) open?.invoke()
                else scope.launch { downloads.fetch(id, today, manual = true) }
            }) {
                Text(stringResource(when (state) {
                    OnlineDownloadState.DOWNLOADING -> R.string.gate_all_downloads
                    OnlineDownloadState.WAITING_TO_RETRY -> R.string.gate_retry_now
                    OnlineDownloadState.FAILED -> R.string.gate_retry
                    else -> if (online) R.string.gate_update else R.string.gate_connect
                }))
            }
        }
    }
}

@Composable
internal fun OnlineGateStatus(downloads: OnlineGateDownloads, id: StationId, day: LocalDate,
                             today: LocalDate, zone: ZoneId, revision: Long, online: Boolean,
                             explanation: @Composable () -> Unit = {}, retry: () -> Unit, now: () -> Unit) {
    val states by downloads.weekStates.collectAsState()
    val through = savedThrough(onlineCoverage(id, revision), today, zone)
    val state = states[id to day]
    val open = LocalOpenDownloads.current
    val (title, action) = when {
        state == OnlineDownloadState.FAILED -> R.string.gate_failed to R.string.gate_retry
        !online || state == OnlineDownloadState.OFFLINE -> R.string.gate_offline to R.string.gate_connect
        state == OnlineDownloadState.DOWNLOADING -> R.string.gate_downloading to R.string.gate_all_downloads
        state == OnlineDownloadState.WAITING_TO_RETRY -> R.string.gate_waiting to R.string.gate_retry_now
        through != null && through < today -> R.string.gate_expired to R.string.gate_download
        else -> R.string.detail_download to R.string.gate_download
    }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            if (title == R.string.gate_downloading) LinearProgressIndicator() else {
                Text(stringResource(R.string.detail_no_week) + " " + day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                explanation()
                Text(stringResource(if (online) R.string.gate_expectation else R.string.gate_expectation_offline))
                if (state == OnlineDownloadState.WAITING_TO_RETRY) Text(stringResource(R.string.gate_retry_expectation))
                if (title != R.string.gate_expired) OnlineValidity(through, today)
            }
            Row {
                TextButton(enabled = online && (state != OnlineDownloadState.DOWNLOADING || open != null), onClick = {
                    if (state == OnlineDownloadState.DOWNLOADING) open?.invoke() else retry()
                }) { Text(stringResource(action)) }
                TextButton(onClick = now) { Text(stringResource(R.string.chart_return)) }
            }
        }
    }
}
