package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.android.core.station.StationId
import org.junit.Assert.*
import org.junit.Test

/** Ports the pinned iOS `BackgroundDownloadTests`; the identifier cases belong to JobScheduler's single job ID. */
class BackgroundDownloadPolicyTest {
    private val tide = StationId("tide")
    private val current = StationId("current")
    /** A tide port that derived current gates are fitted through. */
    private val reference = StationId("reference")
    private val isCurrentWork: (StationId) -> Boolean = { it == current || it == reference }
    private val inView = PhoneSelection(0, false)
    private val nearby = PhoneSelection(25, false)
    private val everything = PhoneSelection(150, false)
    private val currentsOnly = PhoneSelection(0, true)

    private fun allowed(selection: PhoneSelection, states: Map<StationId, TideDownloadState> = emptyMap(), online: Boolean = false,
                        pause: TideDownloadState? = null) =
        backgroundDownloadsAllowed(selection, states, isCurrentWork, online, pause)

    @Test fun allCurrentsContinuesADerivedGatesReferenceFit() {
        assertTrue(allowed(currentsOnly, mapOf(reference to TideDownloadState.DOWNLOADING)))
    }

    @Test fun stationsWaitingForTheNetworkStillContinueInTheBackground() {
        // Selected in a dead zone: the job's network constraint holds it until a connection returns.
        assertTrue(allowed(nearby, mapOf(tide to TideDownloadState.WAITING_FOR_NETWORK), pause = TideDownloadState.WAITING_FOR_NETWORK))
        assertFalse(allowed(inView, mapOf(tide to TideDownloadState.WAITING_FOR_NETWORK)))
    }

    @Test fun dataSaverAllowsNoBackgroundWork() {
        assertFalse(allowed(everything, mapOf(tide to TideDownloadState.DOWNLOADING), online = true, pause = TideDownloadState.DATA_SAVER))
        assertTrue(allowed(everything, mapOf(tide to TideDownloadState.DOWNLOADING), pause = TideDownloadState.WAITING_FOR_NETWORK))
    }

    private val working = BackgroundDownloadCount(ready = 1, total = 3, active = true, waitingToRetry = false)
    private val stalled = working.copy(waitingToRetry = true)

    @Test fun runContinuesWhileWorkRemainsAndFinishesWhenDone() {
        val monitor = BackgroundRunMonitor()
        assertTrue(monitor.keepRunning(working, 0, null, true, 0))
        assertFalse(monitor.keepRunning(working.copy(active = false), 0, null, true, 2_000))
    }

    @Test fun runEndsWhenTheStationListNeverLoaded() {
        assertFalse(BackgroundRunMonitor().keepRunning(null, 0, null, true, 0))
    }

    @Test fun runEndsWhenTheSelectionNoLongerAllowsBackgroundWork() {
        assertFalse(BackgroundRunMonitor().keepRunning(working, 0, null, false, 0))
    }

    @Test fun runEndsWhenNothingFinishesForTwoFullBackoffs() {
        val monitor = BackgroundRunMonitor(stallMillis = 1_800_000)
        assertTrue(monitor.keepRunning(stalled, 0, null, true, 0))
        // A retry attempt between backoffs moves without anything finishing; it must not reset the stall.
        assertTrue(monitor.keepRunning(working, 0, null, true, 900_000))
        assertTrue(monitor.keepRunning(stalled, 0, null, true, 1_799_999))
        assertFalse(monitor.keepRunning(stalled, 0, null, true, 1_800_000))
    }

    @Test fun aSavedModelRestartsTheStall() {
        // Direct requests and online weeks save models too, without changing the selection's ready count.
        val monitor = BackgroundRunMonitor(stallMillis = 1_800_000)
        assertTrue(monitor.keepRunning(working, 7, null, true, 0))
        assertTrue(monitor.keepRunning(working, 8, null, true, 1_700_000))
        assertTrue(monitor.keepRunning(working, 8, null, true, 3_400_000))
        assertFalse(monitor.keepRunning(working, 8, null, true, 3_500_000))
    }

    @Test fun aReadyCountChangeAloneIsNotProgress() {
        // Rows cleared on recovery or a narrower selection change the counts but save nothing.
        val monitor = BackgroundRunMonitor(stallMillis = 1_800_000)
        assertTrue(monitor.keepRunning(working.copy(ready = 50, total = 60), 3, null, true, 0))
        assertTrue(monitor.keepRunning(working.copy(ready = 5, total = 10), 3, null, true, 1_000_000))
        assertTrue(monitor.keepRunning(working.copy(ready = 6, total = 10), 3, null, true, 1_700_000))
        assertFalse(monitor.keepRunning(working.copy(ready = 6, total = 10), 3, null, true, 1_800_000))
    }

    @Test fun aRunStartedBeforeTheNetworkWatcherCatchesUpIsNotEndedAtOnce() {
        val monitor = BackgroundRunMonitor(networkGraceMillis = 5_000, startupMillis = 60_000)
        assertTrue(monitor.keepRunning(working, 0, TideDownloadState.WAITING_FOR_NETWORK, true, 0))
        assertTrue(monitor.keepRunning(working, 0, TideDownloadState.WAITING_FOR_NETWORK, true, 30_000))
        assertTrue(monitor.keepRunning(working, 0, null, true, 32_000))
        // Once online, the ordinary handoff grace applies.
        assertTrue(monitor.keepRunning(working, 0, TideDownloadState.WAITING_FOR_NETWORK, true, 40_000))
        assertFalse(monitor.keepRunning(working, 0, TideDownloadState.WAITING_FOR_NETWORK, true, 45_000))
        val never = BackgroundRunMonitor(networkGraceMillis = 5_000, startupMillis = 60_000)
        assertTrue(never.keepRunning(working, 0, TideDownloadState.WAITING_FOR_NETWORK, true, 0))
        assertFalse(never.keepRunning(working, 0, TideDownloadState.WAITING_FOR_NETWORK, true, 60_000))
    }

    @Test fun permanentlyFailedStationsAreFinishedButNotReady() {
        val c = count(AutomaticProgress(setOf(tide, current), emptySet(), emptySet(), failed = setOf(current)))
        assertEquals(BackgroundDownloadCount(ready = 1, total = 2, active = false, waitingToRetry = false), c)
    }

    @Test fun runEndsForDataSaverAndAfterLosingTheNetwork() {
        assertFalse(BackgroundRunMonitor().keepRunning(working, 0, TideDownloadState.DATA_SAVER, true, 0))
        val monitor = BackgroundRunMonitor(networkGraceMillis = 5_000)
        assertTrue(monitor.keepRunning(working, 0, TideDownloadState.WAITING_FOR_NETWORK, true, 0))
        // A handoff restores the network inside the grace period.
        assertTrue(monitor.keepRunning(working, 0, null, true, 2_000))
        assertTrue(monitor.keepRunning(working, 0, TideDownloadState.WAITING_FOR_NETWORK, true, 4_000))
        assertFalse(monitor.keepRunning(working, 0, TideDownloadState.WAITING_FOR_NETWORK, true, 9_000))
    }

    @Test fun restoredNearbyDownloadsRequestBackgroundProcessing() {
        assertTrue(allowed(nearby, mapOf(tide to TideDownloadState.DOWNLOADING)))
    }

    @Test fun restoredWidestTierRequestsBackgroundProcessing() {
        assertTrue(allowed(everything, mapOf(tide to TideDownloadState.DOWNLOADING)))
    }

    @Test fun allCurrentsCanResumeWithoutAWiderTideTier() {
        assertTrue(allowed(currentsOnly, mapOf(current to TideDownloadState.WAITING_TO_RETRY)))
    }

    @Test fun automaticDownloadsDoNotRequestBackgroundProcessing() {
        assertFalse(allowed(inView, mapOf(tide to TideDownloadState.DOWNLOADING)))
    }

    @Test fun completedCurrentsDoNotAuthorizeAutomaticTideDownloads() {
        assertFalse(allowed(currentsOnly, mapOf(tide to TideDownloadState.DOWNLOADING)))
    }

    @Test fun failedCurrentsDoNotAuthorizeAutomaticTideDownloads() {
        assertFalse(allowed(currentsOnly, mapOf(current to TideDownloadState.RETRY, tide to TideDownloadState.DOWNLOADING)))
    }

    @Test fun downloadingSelectedCurrentCanContinueInTheBackground() {
        assertTrue(allowed(currentsOnly, mapOf(current to TideDownloadState.DOWNLOADING)))
    }

    @Test fun selectedOnlineCurrentsCanResumeWithoutAnyFittedWork() {
        assertTrue(allowed(currentsOnly, online = true))
    }

    @Test fun automaticOnlineCurrentsDoNotRequestBackgroundProcessing() {
        assertFalse(allowed(inView, online = true))
    }

    @Test fun completedDownloadsDoNotRequestBackgroundProcessing() {
        assertFalse(allowed(nearby))
        assertFalse(allowed(nearby, mapOf(tide to TideDownloadState.RETRY)))
    }

    @Test fun queuedStationsStillCheckingTheirSavedModelDoNotRequestBackgroundProcessing() {
        // Every candidate is queued while its saved model is checked; reopening a finished app must not start the job.
        assertFalse(allowed(everything, mapOf(tide to TideDownloadState.QUEUED, current to TideDownloadState.QUEUED)))
    }

    @Test fun onlineWeeksCountWhileDownloadingOrWaiting() {
        assertTrue(onlineDownloadsActive(listOf(OnlineDownloadState.FAILED, OnlineDownloadState.WAITING_TO_RETRY)))
        assertTrue(onlineDownloadsActive(listOf(OnlineDownloadState.DOWNLOADING)))
        assertFalse(onlineDownloadsActive(listOf(OnlineDownloadState.FAILED, OnlineDownloadState.OFFLINE)))
    }

    private fun count(progress: AutomaticProgress?, states: Map<StationId, TideDownloadState> = emptyMap(),
                      weeks: List<OnlineDownloadState> = emptyList(), starting: Boolean = false) =
        BackgroundDownloadCount.of(progress, states, weeks, starting)

    @Test fun countReportsReadyOfTotalWhileStationsRemain() {
        val c = count(AutomaticProgress(setOf(tide, current), setOf(current), emptySet()), mapOf(current to TideDownloadState.DOWNLOADING))
        assertEquals(BackgroundDownloadCount(ready = 1, total = 2, active = true, waitingToRetry = false), c)
    }

    @Test fun countWaitsToRetryOnlyWhenNothingElseCanMove() {
        val waiting = AutomaticProgress(setOf(tide, current), setOf(tide), setOf(tide))
        assertTrue(count(waiting, mapOf(tide to TideDownloadState.WAITING_TO_RETRY)).waitingToRetry)
        // Another candidate is due, so the count is the better thing to show.
        val due = AutomaticProgress(setOf(tide, current), setOf(tide, current), setOf(tide))
        assertFalse(count(due, mapOf(tide to TideDownloadState.WAITING_TO_RETRY)).waitingToRetry)
        assertFalse(count(waiting, mapOf(tide to TideDownloadState.WAITING_TO_RETRY), listOf(OnlineDownloadState.DOWNLOADING)).waitingToRetry)
    }

    @Test fun aRequestedDownloadWaitingToRetryContinuesInTheBackground() {
        assertTrue(allowed(nearby, mapOf(tide to TideDownloadState.WAITING_TO_RETRY)))
        assertFalse(allowed(currentsOnly, mapOf(tide to TideDownloadState.WAITING_TO_RETRY)))
    }

    @Test fun theHeldOriginFollowsTheFixAndIsDroppedWhenLocationIsNotAllowed() {
        val here = io.openwaters.slackwater.android.core.station.Coordinate(48.4, -123.4)
        val there = io.openwaters.slackwater.android.core.station.Coordinate(49.3, -123.1)
        val located = io.openwaters.slackwater.android.core.station.LocationSlot.Located(
            io.openwaters.slackwater.android.core.station.LocationFix(there, 10.0, 0), approximate = false)
        assertEquals(there, heldOrigin(located, here))
        assertEquals(here, heldOrigin(io.openwaters.slackwater.android.core.station.LocationSlot.Locating, here))
        assertEquals(here, heldOrigin(io.openwaters.slackwater.android.core.station.LocationSlot.Failed, here))
        assertNull(heldOrigin(io.openwaters.slackwater.android.core.station.LocationSlot.NotDetermined, here))
        assertNull(heldOrigin(io.openwaters.slackwater.android.core.station.LocationSlot.Denied, here))
    }

    @Test fun countIsInactiveOnlyWhenEverythingIsDone() {
        val done = AutomaticProgress(setOf(tide), emptySet(), emptySet())
        assertFalse(count(done, mapOf(current to TideDownloadState.RETRY)).active)
        assertTrue(count(done, mapOf(current to TideDownloadState.QUEUED)).active)
        assertTrue(count(done, weeks = listOf(OnlineDownloadState.WAITING_TO_RETRY)).active)
        assertTrue(count(done, starting = true).active)
        assertFalse(count(null).active)
    }
}
