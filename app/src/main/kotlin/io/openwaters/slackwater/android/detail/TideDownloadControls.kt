package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
    TideDownloadActions(states[id], { model.launchEdit { downloads.request(id) } },
        { model.launchEdit { downloads.cancel(id) } })
}

@Composable
internal fun TideDownloadActions(state: TideDownloadState?, request: () -> Unit, cancel: () -> Unit) {
    if (state == null) {
        Button(onClick = request) { Text(stringResource(R.string.detail_download)) }
        return
    }
    Text(stringResource(when (state) {
        TideDownloadState.QUEUED -> R.string.detail_queued
        TideDownloadState.DOWNLOADING -> R.string.tide_download_active
        TideDownloadState.WAITING_FOR_NETWORK -> R.string.tide_download_network
        TideDownloadState.DATA_SAVER -> R.string.tide_download_restricted
        TideDownloadState.RETRY -> R.string.tide_download_failed
    }))
    if (state == TideDownloadState.DOWNLOADING) LinearProgressIndicator()
    Row {
        if (state == TideDownloadState.RETRY) {
            TextButton(onClick = request) { Text(stringResource(R.string.table_sheet_retry)) }
        }
        TextButton(onClick = cancel) { Text(stringResource(R.string.alert_cancel)) }
    }
}
