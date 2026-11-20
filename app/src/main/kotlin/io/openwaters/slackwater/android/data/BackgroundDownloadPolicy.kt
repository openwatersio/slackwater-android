package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.android.core.station.StationId

/**
 * Whether the selected downloads may continue after the app leaves the screen, following iOS
 * `BackgroundDownloadSubmission`. Automatic Nearby alone never continues. A wider radius continues
 * any transfer; All current stations continues only while a current gate transfers. Selected
 * online gates continue while their weeks download.
 *
 * Android queues every candidate and checks its saved model first, so a station counts once it is
 * downloading, waiting to retry or waiting for the network, not while it is merely queued:
 * reopening the app with everything already fitted must not start a background job. Leaving the
 * app before any selected station gets that far leaves the downloads for the next launch. [isCurrentWork] covers fitted
 * current gates and the reference tides that derived gates are fitted through, since All current
 * stations on Android includes derived gates. Data Saver keeps background transfers off metered
 * networks, so it allows nothing.
 */
internal fun backgroundDownloadsAllowed(
    selection: PhoneSelection,
    states: Map<StationId, TideDownloadState>,
    isCurrentWork: (StationId) -> Boolean,
    onlineActive: Boolean,
    networkPause: TideDownloadState? = null,
): Boolean {
    if (networkPause == TideDownloadState.DATA_SAVER) return false
    // A station waits for the network only after its saved model showed it needs downloading, so it
    // counts; the job's network constraint holds the run until a connection returns.
    val transferring = states.filterValues {
        it == TideDownloadState.DOWNLOADING || it == TideDownloadState.WAITING_TO_RETRY || it == TideDownloadState.WAITING_FOR_NETWORK
    }.keys
    val fitted = if (selection.radiusKm > 0) transferring.isNotEmpty() else selection.allCurrents && transferring.any(isCurrentWork)
    return fitted || (selection.backgroundRequested && onlineActive)
}

/**
 * Decides when a background run ends. As on iOS, a run that ends is not restarted; the next
 * launch resumes from the saved requests and IWLS weeks. Android, unlike iOS, does not stop a
 * task that shows no progress, so a station that keeps failing or a transfer that hangs would
 * hold the job open. The run ends once nothing has been saved for [stallMillis], twice the longest
 * retry backoff so a retry attempt in between cannot reset it. Progress is a saved model or
 * online week, the only sign that a transfer finished rather than a row being cleared.
 */
internal class BackgroundRunMonitor(
    private val stallMillis: Long = 30 * 60_000L,
    private val networkGraceMillis: Long = 5_000L,
    private val startupMillis: Long = 60_000L,
) {
    private var startedAt: Long? = null
    private var progressAt: Long? = null
    private var saved = Long.MIN_VALUE
    private var offlineSince: Long? = null
    private var seenOnline = false

    /** True while the run should keep going. [savedModels] counts model writes and only ever grows. */
    fun keepRunning(count: BackgroundDownloadCount?, savedModels: Long, networkPause: TideDownloadState?,
                    selectionAllowsBackground: Boolean, now: Long): Boolean {
        val started = startedAt ?: now.also { startedAt = it }
        // The station list never loaded, or nothing is left.
        if (!selectionAllowsBackground || count == null || !count.active) return false
        when (networkPause) {
            TideDownloadState.DATA_SAVER -> return false
            TideDownloadState.WAITING_FOR_NETWORK -> {
                val since = offlineSince ?: now.also { offlineSince = it }
                // JobScheduler starts the run when a network validates, which the app's own watcher can
                // report a moment later; after that, a Wi-Fi to cellular handoff briefly reports no network.
                val grace = if (seenOnline) networkGraceMillis else startupMillis - (since - started)
                if (now - since >= grace) return false
            }
            else -> {
                offlineSince = null
                seenOnline = true
            }
        }
        if (progressAt == null || savedModels > saved) progressAt = now
        saved = savedModels
        return now - progressAt!! < stallMillis
    }
}

/** The online gate weeks that count as active: downloading or waiting to retry, not failed or offline. */
internal fun onlineDownloadsActive(weeks: Collection<OnlineDownloadState>): Boolean =
    weeks.any { it == OnlineDownloadState.DOWNLOADING || it == OnlineDownloadState.WAITING_TO_RETRY }

/** What the background notification shows. Counts are the automatic selection's stations. */
internal data class BackgroundDownloadCount(val ready: Int, val total: Int, val active: Boolean, val waitingToRetry: Boolean) {
    companion object {
        fun of(progress: AutomaticProgress?, states: Map<StationId, TideDownloadState>, onlineWeeks: Collection<OnlineDownloadState>,
               onlineStarting: Boolean): BackgroundDownloadCount {
            val total = progress?.stations?.size ?: 0
            val pending = progress?.pending.orEmpty()
            val working = states.values.filter { it != TideDownloadState.RETRY }
            // Due candidates count as moving: the loop claims them next, and the gap between jobs is not a stall.
            val moving = (pending - progress?.waiting.orEmpty()).isNotEmpty() ||
                working.any { it == TideDownloadState.QUEUED || it == TideDownloadState.DOWNLOADING } ||
                OnlineDownloadState.DOWNLOADING in onlineWeeks || onlineStarting
            val deferred = progress?.waiting.orEmpty().isNotEmpty() || TideDownloadState.WAITING_TO_RETRY in working ||
                OnlineDownloadState.WAITING_TO_RETRY in onlineWeeks
            val active = pending.isNotEmpty() || working.isNotEmpty() || onlineDownloadsActive(onlineWeeks) || onlineStarting
            // As on iOS, "Waiting to retry…" shows only when nothing else can make progress.
            val failed = progress?.failed.orEmpty().size
            return BackgroundDownloadCount((total - pending.size - failed).coerceAtLeast(0), total, active, deferred && !moving)
        }
    }
}
