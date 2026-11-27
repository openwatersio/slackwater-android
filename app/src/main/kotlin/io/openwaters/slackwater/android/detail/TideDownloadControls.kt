package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.IOException
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.TideDownloadState
import io.openwaters.slackwater.android.data.TidePreviewDownloads
import io.openwaters.slackwater.android.stations.StationListModel

@Composable
internal fun TideDownloadControls(id: StationId, model: StationListModel) {
    val downloads = TidePreviewDownloads.get(LocalContext.current)
    val states by downloads.states.collectAsState()
    // With no row this is Download; on a failed or waiting row it is Retry, which keeps a reference row reference-only.
    TideDownloadEdits(states[id], model::launchEdit, { if (states[id] == null) downloads.request(id) else downloads.retry(id) }, { downloads.cancel(id) })
}

@Composable
internal fun TideDownloadEdits(state: TideDownloadState?, launch: (suspend () -> Unit) -> Unit,
                              request: suspend () -> Unit, cancel: suspend () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    fun edit(action: suspend () -> Unit) {
        launch {
            try {
                action()
                failed = false
            } catch (error: IOException) {
                failed = true
                throw error
            }
        }
    }
    if (failed) Text(stringResource(R.string.tide_download_edit_failed))
    TideDownloadActions(state, { edit(request) }, { edit(cancel) })
}

@Composable
internal fun TideDownloadActions(state: TideDownloadState?, request: () -> Unit, cancel: () -> Unit) {
    if (state == null) {
        Button(onClick = request) { Text(stringResource(R.string.detail_download)) }
        return
    }
    Text(stringResource(when (state) {
        TideDownloadState.QUEUED -> R.string.detail_queued
        TideDownloadState.DOWNLOADING -> R.string.gate_downloading
        TideDownloadState.WAITING_FOR_NETWORK -> R.string.tide_download_network
        TideDownloadState.DATA_SAVER -> R.string.tide_download_restricted
        TideDownloadState.WAITING_TO_RETRY -> R.string.gate_waiting
        TideDownloadState.RETRY -> R.string.gate_failed
    }))
    if (state == TideDownloadState.DOWNLOADING) LinearProgressIndicator()
    Row {
        if (state == TideDownloadState.RETRY) {
            TextButton(onClick = request) { Text(stringResource(R.string.table_sheet_retry)) }
        } else if (state == TideDownloadState.WAITING_TO_RETRY) {
            // Matches the online gate card's waiting state.
            TextButton(onClick = request) { Text(stringResource(R.string.gate_retry_now)) }
        }
        TextButton(onClick = cancel) { Text(stringResource(R.string.alert_cancel)) }
    }
}
