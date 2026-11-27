package io.openwaters.slackwater.android.stations

import io.openwaters.slackwater.android.data.BackgroundDownloadCount
import io.openwaters.slackwater.android.data.OnlineDownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsRowStateTest {
    private fun count(ready: Int, total: Int, active: Boolean) = BackgroundDownloadCount(ready, total, active, waitingToRetry = false)

    @Test fun onlineWithWorkLeftShowsTheCount() {
        assertEquals(DownloadsRowState.Downloading(3, 8, failed = false),
            DownloadsRowState.of(online = true, count(3, 8, active = true), failed = false))
    }

    @Test fun onlineWithEverythingReadySaysSo() {
        assertEquals(DownloadsRowState.Online(complete = true, failed = false),
            DownloadsRowState.of(online = true, count(8, 8, active = false), failed = false))
    }

    @Test fun onlineWithNothingWantedIsNotComplete() {
        assertEquals(DownloadsRowState.Online(complete = false, failed = false),
            DownloadsRowState.of(online = true, count(0, 0, active = false), failed = false))
        assertEquals(DownloadsRowState.Online(complete = false, failed = false),
            DownloadsRowState.of(online = true, null, failed = false))
    }

    @Test fun offlineNeverShowsACountAndNamesWhatIsMissing() {
        assertEquals(DownloadsRowState.Offline(remaining = 5, complete = false),
            DownloadsRowState.of(online = false, count(3, 8, active = true), failed = false))
        assertEquals(DownloadsRowState.Offline(remaining = 0, complete = true),
            DownloadsRowState.of(online = false, count(8, 8, active = false), failed = true))
    }

    @Test fun offlineWithNothingWantedHasNothingMissing() {
        val nothing = DownloadsRowState.Offline(remaining = 0, complete = false)
        assertEquals(nothing, DownloadsRowState.of(online = false, null, failed = false))
        assertEquals(nothing, DownloadsRowState.of(online = false, count(0, 0, active = false), failed = false))
    }

    @Test fun aPausedQueueDoesNotStallAWeekThatIsDownloading() {
        assertEquals(true, downloadsStalled(queuePaused = true, emptyList()))
        assertEquals(true, downloadsStalled(queuePaused = true, listOf(OnlineDownloadState.WAITING_TO_RETRY)))
        assertEquals(false, downloadsStalled(queuePaused = true, listOf(OnlineDownloadState.DOWNLOADING)))
        assertEquals(false, downloadsStalled(queuePaused = false, emptyList()))
    }

    @Test fun workThatCannotMoveIsNotCalledDownloading() {
        val waiting = DownloadsRowState.Online(complete = false, failed = false)
        assertEquals(waiting, DownloadsRowState.of(online = true, count(3, 8, active = true), failed = false, stalled = true))
        assertEquals(waiting, DownloadsRowState.of(online = true,
            BackgroundDownloadCount(3, 8, active = true, waitingToRetry = true), failed = false))
    }

    @Test fun aFailedDownloadMarksTheOnlineStates() {
        assertEquals(DownloadsRowState.Online(complete = false, failed = true),
            DownloadsRowState.of(online = true, count(7, 8, active = false), failed = true))
        assertEquals(DownloadsRowState.Downloading(1, 8, failed = true),
            DownloadsRowState.of(online = true, count(1, 8, active = true), failed = true))
    }
}
