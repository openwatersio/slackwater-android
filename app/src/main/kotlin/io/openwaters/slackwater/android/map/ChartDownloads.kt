package io.openwaters.slackwater.android.map

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.map.ChartPackSpec
import io.openwaters.slackwater.android.core.map.desiredChartPacks
import io.openwaters.slackwater.android.core.station.LocationPermission
import io.openwaters.slackwater.android.core.station.LocationSlot
import io.openwaters.slackwater.android.core.station.fixCoordinate
import androidx.compose.runtime.key
import io.openwaters.slackwater.android.detail.TideDownloadEdits
import io.openwaters.slackwater.android.data.TidePreviewDownloads
import io.openwaters.slackwater.android.data.OnlineGateDownloads
import io.openwaters.slackwater.android.data.OnlineDownloadState
import io.openwaters.slackwater.android.data.ModelKind
import io.openwaters.slackwater.android.data.ModelStore
import io.openwaters.slackwater.android.stations.StationListModel
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Publish unknown inputs during retries so we can add coverage without retiring previously saved regions. */
private fun <T> Flow<T>.chartInput(): Flow<T?> = transform<T, T?> { emit(it) }
    .retryWhen { error, attempt ->
        if (error !is IOException) return@retryWhen false
        emit(null)
        delay(1_000L shl attempt.coerceAtMost(6).toInt())
        true
    }.onStart { emit(null) }

internal data class DesiredChartCoverage(
    val specs: Set<ChartPackSpec>,
    val pendingFix: Boolean,
    val incompleteStations: Boolean,
)

internal fun chartCoverage(context: Context): Flow<DesiredChartCoverage> {
    val list = StationListModel.get(context)
    val models = ModelStore.get(context)
    val stored = models.revision.map {
        ModelKind.entries.flatMap { kind -> models.storedIds(kind) }.toSet()
    }.chartInput()
    val saved = list.places.places.map { it.getOrThrow() }.chartInput()
    val requested = list.places.downloads.state.map { result -> result.getOrThrow().let { it.requested + it.previewRequested } }.chartInput()
    val stations = combine(list.catalog, saved, stored, requested) { catalog, places, modelIds, requests ->
        val incomplete = catalog == null || places == null || modelIds == null || requests == null
        val ids = modelIds.orEmpty() + requests.orEmpty() + places?.favorites.orEmpty() + places?.recents.orEmpty()
        ids.mapNotNull { catalog?.byId?.get(it) } to incomplete
    }
    val locations = combine(list.state, list.location.inputs) { state, inputs ->
        val allowed = inputs.permission != LocationPermission.NOT_GRANTED
        val slot = state?.slot
        val pending = slot == null || slot == LocationSlot.Locating || slot == LocationSlot.Failed
        slot?.fixCoordinate.takeIf { allowed } to (allowed && pending)
    }.distinctUntilChanged()
    return combine(stations, locations) { (identities, incomplete), (fix, pending) ->
        DesiredChartCoverage(desiredChartPacks(fix, identities), pending, incomplete)
    }.distinctUntilChanged()
}

@Composable
internal fun ChartDownloads(manager: ChartPackManager, close: () -> Unit) {
    val summary by manager.summary.collectAsState()
    var confirming by rememberSaveable { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(onDismissRequest = { confirming = false }, title = { Text(stringResource(R.string.chart_remove_question)) },
            confirmButton = { TextButton(onClick = { confirming = false; manager.remove() }) {
                Text(stringResource(R.string.chart_remove))
            } }, dismissButton = { TextButton(onClick = { confirming = false }) {
                Text(stringResource(R.string.alert_cancel))
            } })
        return
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.chart_downloads)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val model = StationListModel.get(LocalContext.current)
                val store = model.places.downloads
                val selection by store.selection.collectAsState(initial = null)
                selection?.let { choices ->
                    for (km in listOf(25, 150)) {
                        DownloadChoice(stringResource(if (km == 25) R.string.download_25 else R.string.download_150), choices.radiusKm >= km) {
                            model.launchEdit { store.selectRadius(km, it) }
                        }
                    }
                    DownloadChoice(stringResource(R.string.download_all_currents), choices.allCurrents) {
                        model.launchEdit { store.selectCurrents(it) }
                    }
                }
                val downloads = TidePreviewDownloads.get(LocalContext.current)
                val states by downloads.states.collectAsState()
                val catalog by model.catalog.collectAsState()
                if (states.isNotEmpty()) Text(stringResource(R.string.tide_downloads))
                for ((id, state) in states.entries.sortedBy { catalog?.byId?.get(it.key)?.name ?: it.key.value }) {
                    key(id) {
                        Text(catalog?.byId?.get(id)?.name ?: id.value)
                        TideDownloadEdits(state, model::launchEdit, { downloads.request(id) }, { downloads.cancelFromManager(id) })
                    }
                }
                val onlineGates = OnlineGateDownloads.get(LocalContext.current)
                val gateStates by onlineGates.weekStates.collectAsState()
                for ((week, state) in gateStates.entries.sortedBy { catalog?.byId?.get(it.key.first)?.name ?: it.key.first.value }) {
                    val (id, day) = week
                    Text(catalog?.byId?.get(id)?.name ?: id.value)
                    Text(day.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)))
                    when (state) {
                        OnlineDownloadState.DOWNLOADING -> LinearProgressIndicator()
                        OnlineDownloadState.OFFLINE -> Text(stringResource(R.string.gate_offline))
                        OnlineDownloadState.WAITING_TO_RETRY, OnlineDownloadState.FAILED ->
                            TextButton(onClick = { model.launchEdit { onlineGates.fetch(id, day, manual = true) } }) {
                                Text(stringResource(if (state == OnlineDownloadState.FAILED) R.string.gate_retry else R.string.gate_retry_now))
                            }
                    }
                }
                Text(stringResource(R.string.chart_offline))
                Text(stringResource(R.string.chart_coverage))
                summary.problem?.let {
                    Text(stringResource(when (it) {
                        ChartPackProblem.Quota -> R.string.chart_quota
                        ChartPackProblem.Storage -> R.string.chart_storage
                        ChartPackProblem.Download -> R.string.chart_failed
                    }))
                }
                if (summary.removing) {
                    if (summary.canRetry) {
                        TextButton(onClick = manager::retry) { Text(stringResource(R.string.table_sheet_retry)) }
                    } else {
                        LinearProgressIndicator()
                    }
                } else if (summary.removed) {
                    Text(stringResource(R.string.chart_removed))
                    TextButton(onClick = manager::resume) { Text(stringResource(R.string.detail_download)) }
                } else {
                    Text(stringResource(R.string.chart_ready_count, summary.ready, summary.total))
                    if (summary.bytes > 0) Text(stringResource(R.string.chart_area_bytes, Formatter.formatShortFileSize(LocalContext.current, summary.bytes)))
                    if (summary.downloading && !summary.paused && summary.total > 0) {
                        LinearProgressIndicator(progress = { summary.ready.toFloat() / summary.total })
                    }
                    if (summary.dataSaver) Text(stringResource(R.string.chart_data_saver))
                    if (summary.paused) Text(stringResource(R.string.chart_paused))
                    if (summary.inputsUnavailable) Text(stringResource(R.string.chart_inputs_unavailable))
                    FlowRow {
                        if (summary.paused) {
                            TextButton(onClick = manager::resume) { Text(stringResource(R.string.chart_resume)) }
                        } else if (summary.ready < summary.total) {
                            TextButton(onClick = manager::pause) { Text(stringResource(R.string.chart_pause)) }
                        }
                        if (summary.canRetry) {
                            TextButton(onClick = manager::retry) { Text(stringResource(R.string.table_sheet_retry)) }
                        }
                        TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.chart_remove)) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } })
}

@Composable
private fun DownloadChoice(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.heightIn(min = 48.dp).toggleable(checked, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = change),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(checked, onCheckedChange = null)
        Text(label)
    }
}
