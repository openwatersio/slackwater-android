package io.openwaters.slackwater.android.stations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.BuildConfig
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.data.BackgroundDownloadCount
import io.openwaters.slackwater.android.data.OnlineDownloadState
import io.openwaters.slackwater.android.data.OnlineGateDownloads
import io.openwaters.slackwater.android.data.TidePreviewDownloads
import io.openwaters.slackwater.android.data.retryAlertReads

private val Amber = Color(0xFFF0C860)

/** What the Downloads row says (list §8). [failed] turns an online state amber. */
internal sealed interface DownloadsRowState {
    /** [total] is the automatic set; zero while the only work is a station opened directly or an online current. */
    data class Downloading(val ready: Int, val total: Int, val failed: Boolean) : DownloadsRowState
    data class Online(val complete: Boolean, val failed: Boolean) : DownloadsRowState
    /** [remaining] is zero both when everything is ready and when nothing is wanted; [complete] tells them apart. */
    data class Offline(val remaining: Int, val complete: Boolean) : DownloadsRowState

    companion object {
        /**
         * The row says "Downloading" only while the device is online and work can move. [stalled] is queued work
         * that a network pause holds back ([downloadsStalled]); a retry wait is read from [count]. The row stays
         * calm for both.
         */
        fun of(online: Boolean, count: BackgroundDownloadCount?, failed: Boolean, stalled: Boolean = false): DownloadsRowState {
            val ready = count?.ready ?: 0
            val total = count?.total ?: 0
            val complete = total > 0 && ready == total
            return when {
                online && count?.active == true && !stalled && !count.waitingToRetry -> Downloading(ready, total, failed)
                online -> Online(complete, failed)
                else -> Offline((total - ready).coerceAtLeast(0), complete)
            }
        }
    }
}

/**
 * The tide queue's pause (no network, or Data Saver) stops only the tide queue. An online current's week still
 * downloads while the app is on screen, so the pause stalls the row only when no week is moving.
 */
internal fun downloadsStalled(queuePaused: Boolean, weeks: Collection<OnlineDownloadState>): Boolean =
    queuePaused && OnlineDownloadState.DOWNLOADING !in weeks

@Composable
private fun rememberDownloadsRowState(): DownloadsRowState {
    val context = LocalContext.current
    val queue = remember(context) { TidePreviewDownloads.get(context) }
    val gates = remember(context) { OnlineGateDownloads.get(context) }
    val progress by queue.progress.collectAsState()
    val states by queue.states.collectAsState()
    val pause by queue.networkPause.collectAsState()
    val weeks by gates.weekStates.collectAsState()
    val online by gates.online.collectAsState()
    val count = BackgroundDownloadCount.of(progress, states, weeks.values, onlineStarting = false)
    val failed = progress?.failed.orEmpty().isNotEmpty() || OnlineDownloadState.FAILED in weeks.values
    return DownloadsRowState.of(online, count, failed, stalled = downloadsStalled(pause != null, weeks.values))
}

/** List §8: one rounded card of rows, then the wordmark. [onSupport] is used only in builds that sell Premium. */
internal fun LazyListScope.listFooter(onDownloads: () -> Unit, onSettings: () -> Unit, onSupport: () -> Unit) {
    item(key = "footer") {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    DownloadsRow(rememberDownloadsRowState(), onDownloads)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    FooterRow("⚙︎", stringResource(R.string.settings_title), Modifier.testTag("footerSettings"), onClick = onSettings)
                    if (BuildConfig.PREMIUM_ENABLED) SupportRow(onSupport)
                }
            }
            Spacer(Modifier.height(20.dp))
            val quiet = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            Text(stringResource(R.string.list_wordmark), style = MaterialTheme.typography.titleSmall, color = quiet)
            Text(stringResource(R.string.list_by_open_waters), style = MaterialTheme.typography.bodySmall, color = quiet)
        }
    }
}

@Composable
internal fun DownloadsRow(state: DownloadsRowState, onClick: () -> Unit) {
    val leaf = MaterialTheme.colorScheme.primary
    val calm = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    // Amber only when something needs attention: a failed download, or no signal with stations still missing.
    val tint = when (state) {
        is DownloadsRowState.Downloading -> if (state.failed) Amber else leaf
        is DownloadsRowState.Online -> if (state.failed) Amber else calm
        is DownloadsRowState.Offline -> when {
            state.complete -> leaf
            state.remaining > 0 -> Amber
            else -> calm
        }
    }
    val spoken = when (state) {
        is DownloadsRowState.Downloading ->
            if (state.total > 0) stringResource(R.string.footer_downloading, state.ready, state.total)
            else stringResource(R.string.gate_downloading)
        is DownloadsRowState.Online ->
            stringResource(if (state.complete) R.string.footer_online_complete else R.string.footer_online)
        is DownloadsRowState.Offline -> when {
            state.complete -> stringResource(R.string.footer_offline_complete)
            state.remaining > 0 -> pluralStringResource(R.plurals.footer_offline_remaining, state.remaining, state.remaining)
            else -> stringResource(R.string.footer_offline)
        }
    }
    val label = stringResource(R.string.footer_downloads_label)
    val downloading = state as? DownloadsRowState.Downloading
    val counted = downloading?.takeIf { it.total > 0 }
    FooterRow(
        glyph = when (state) {
            is DownloadsRowState.Downloading -> "↓"
            is DownloadsRowState.Online -> "◉"
            is DownloadsRowState.Offline -> "○"
        },
        title = stringResource(R.string.chart_downloads),
        modifier = Modifier.testTag("footerDownloads").clearAndSetSemantics {
            contentDescription = label
            stateDescription = spoken
            role = Role.Button
            onClick { onClick(); true }
        },
        glyphTint = tint,
        ring = downloading != null,
        // Without a counted set there is no fraction to show, so the ring turns freely, as the notification's bar does.
        progress = counted?.let { it.ready.toFloat() / it.total },
        trailing = counted?.let { "${it.ready}/${it.total}" },
        trailingTint = tint,
        onClick = onClick,
    )
}

@Composable
private fun SupportRow(onClick: () -> Unit) {
    val context = LocalContext.current
    val access = remember(context) { ReminderAccessStore.get(context) }
    val record by remember(access) { access.freeDayRecord.retryAlertReads() }.collectAsState(null)
    // The row waits for the purchase record, so a supporter is never shown the sale first.
    val purchased = record?.purchased ?: return
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    FooterRow(
        glyph = "✦",
        title = stringResource(if (purchased) R.string.footer_supporter else R.string.schedule_support_title),
        modifier = Modifier.testTag("footerSupport"),
        glyphTint = if (purchased) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        caption = if (purchased) stringResource(R.string.premium_purchased) else null,
        onClick = onClick,
    )
}

@Composable
private fun FooterRow(
    glyph: String,
    title: String,
    modifier: Modifier = Modifier,
    glyphTint: Color = MaterialTheme.colorScheme.onSurface,
    ring: Boolean = false,
    progress: Float? = null,
    caption: String? = null,
    trailing: String? = null,
    trailingTint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).then(modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), Alignment.Center) {
            val leaf = MaterialTheme.colorScheme.primary
            if (ring && progress != null) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp),
                color = leaf, trackColor = Color.Transparent, strokeWidth = 2.dp)
            else if (ring) CircularProgressIndicator(Modifier.size(28.dp), color = leaf, trackColor = Color.Transparent,
                strokeWidth = 2.dp)
            Text(glyph, Modifier.clearAndSetSemantics { }, color = glyphTint)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(title)
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, Modifier.padding(horizontal = 8.dp), color = trailingTint,
            style = MaterialTheme.typography.bodyMedium)
        Text("›", Modifier.clearAndSetSemantics { }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}
