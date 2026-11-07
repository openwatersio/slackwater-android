package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.iwls.IwlsException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class OnlineGateDownloadsTest {
    private val day = LocalDate.parse("2026-11-07")
    private val now = Instant.parse("2026-11-07T00:00:00Z")
    private val gate = StationIdentity(StationId("chs-test"), "Test", "", 0.0, 0.0, StationKind.CanadianCurrent, timezone = "UTC")
    private var blocks = emptyList<TimeSpan>()
    private val full = TimeSpan(now.minusSeconds(2 * 86400L), now.plusSeconds(30 * 86400L))
    private val network = MutableStateFlow(true)
    private val saver = MutableStateFlow(false)
    private fun service(fetch: suspend () -> Unit) =
        OnlineGateDownloads({ gate }, { blocks }, { _, _, _ -> fetch() }, network, saver, { now }, retryDelayMillis = 1)

    @Test fun manualRetryStartsANewBoundedCycleAndCoverageSurvivesOffline() = runBlocking {
        var tries = 0
        val downloads = service { if (++tries < 3) throw IwlsException.Transient("offline") else blocks = listOf(full) }
        assertFalse(downloads.fetch(gate.id, day))
        assertFalse(downloads.fetch(gate.id, day))
        assertEquals(2, tries)
        assertTrue(downloads.fetch(gate.id, day, manual = true))
        network.value = false
        assertTrue(downloads.fetch(gate.id, day))
        assertEquals(3, tries)
    }

    @Test fun coveredWeekRefreshIsQuietAndAtMostOncePerDay() = runBlocking {
        blocks = listOf(full.copy(end = now.plusSeconds(15 * 86400L)))
        var tries = 0
        val downloads = service { tries++; throw IwlsException.Transient("unavailable") }
        saver.value = true
        downloads.refreshIfStale(gate.id, day)
        assertEquals(0, tries)
        saver.value = false
        repeat(2) { downloads.refreshIfStale(gate.id, day) }
        assertEquals(1, tries)
        assertTrue(downloads.weekStates.value.isEmpty())
    }

    @Test fun dataSaverAllowsPrefetchOnlyWhenTheWeekNeedsCoverage() = runBlocking {
        var attempts = 0
        saver.value = true
        val downloads = service {
            attempts++
            blocks = listOf(full.copy(end = now.plusSeconds(15 * 86400L)))
        }
        downloads.prefetch(listOf(gate.id))
        downloads.prefetch(listOf(gate.id))
        assertEquals(1, attempts)
    }

    @Test fun prefetchAndDetailShareTheSameTransferEvenInDataSaver() = runBlocking {
        var tries = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val downloads = service { tries++; entered.complete(Unit); release.await(); blocks = listOf(full) }
        saver.value = true
        val request = launch { downloads.fetch(gate.id, day) }
        entered.await()
        val prefetch = launch { downloads.prefetch(listOf(gate.id)) }
        release.complete(Unit)
        joinAll(request, prefetch)
        assertEquals(1, tries)
    }

    @Test fun manualRetryInterruptsBackoffAndPreservesCallerHold() = runBlocking {
        var tries = 0
        val downloads = OnlineGateDownloads({ gate }, { blocks }, { _, _, _ ->
            if (++tries == 1) throw IwlsException.Transient("temporary")
            blocks = listOf(full)
        }, network, saver, { now }, retryDelayMillis = 60_000)
        val automatic = async { downloads.fetch(gate.id, day) }
        downloads.weekStates.first { it[gate.id to day] == OnlineDownloadState.WAITING_TO_RETRY }
        assertTrue(withTimeout(5_000) { downloads.fetch(gate.id, day, manual = true) })
        assertFalse(automatic.await())
        assertEquals(2, tries)
    }

    @Test fun cancelledBackoffWithdrawsTheRetryNotice() = runBlocking {
        val downloads = OnlineGateDownloads({ gate }, { blocks }, { _, _, _ -> throw IwlsException.Transient("retry") },
            network, saver, { now }, retryDelayMillis = 60_000)
        val job = launch { downloads.fetch(gate.id, day) }
        downloads.weekStates.first { it[gate.id to day] == OnlineDownloadState.WAITING_TO_RETRY }
        job.cancelAndJoin()
        assertNull(downloads.weekStates.value[gate.id to day])
    }

    @Test fun cancellingQuietRefreshKeepsItEligibleOnTheSameDay() = runBlocking {
        blocks = listOf(full.copy(end = now.plusSeconds(15 * 86400L)))
        val started = CompletableDeferred<Unit>()
        var attempts = 0
        val downloads = service {
            if (++attempts == 1) {
                started.complete(Unit)
                awaitCancellation()
            }
            blocks = listOf(full)
        }
        val job = launch { downloads.refreshIfStale(gate.id, day) }
        started.await()
        job.cancelAndJoin()
        downloads.refreshIfStale(gate.id, day)
        assertEquals(2, attempts)
    }

    @Test fun openedGateTakesPriorityOverWaitingPrefetches() = runBlocking {
        val second = gate.copy(id = StationId("second"))
        val opened = gate.copy(id = StationId("opened"))
        val stations = listOf(gate, second, opened).associateBy { it.id }
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val openedChecked = CompletableDeferred<Unit>()
        val order = java.util.Collections.synchronizedList(mutableListOf<StationId>())
        val active = java.util.concurrent.atomic.AtomicInteger()
        val downloads = OnlineGateDownloads(stations::get, { id ->
            if (id == opened.id) openedChecked.complete(Unit)
            if (id in order) listOf(full) else emptyList()
        }, { station, _, _ ->
            assertEquals(1, active.incrementAndGet())
            try {
                if (station.id == gate.id) {
                    firstStarted.complete(Unit)
                    release.await()
                }
                order.add(station.id)
            } finally {
                active.decrementAndGet()
            }
        }, network, saver, { now })
        val first = launch { downloads.prefetch(listOf(gate.id)) }
        firstStarted.await()
        val queued = launch { downloads.prefetch(listOf(second.id, opened.id)) }
        val tap = async { downloads.fetch(opened.id, day) }
        withTimeout(5_000) {
            openedChecked.await()
            downloads.weekStates.first { it[opened.id to day] == OnlineDownloadState.DOWNLOADING }
        }
        assertTrue(order.isEmpty())
        release.complete(Unit)
        assertTrue(tap.await())
        joinAll(first, queued)
        assertEquals(listOf(gate.id, opened.id, second.id), order)
    }

    @Test fun invalidGateResponseDoesNotCancelOtherPrefetches() = runBlocking {
        val other = gate.copy(id = StationId("chs-other"))
        val saved = java.util.concurrent.ConcurrentHashMap<StationId, List<TimeSpan>>()
        val downloads = OnlineGateDownloads({ if (it == gate.id) gate else other }, { saved[it].orEmpty() },
            { station, _, _ ->
                require(station.id != gate.id) { "invalid projected speed" }
                saved[station.id] = listOf(full)
            }, network, saver, { now })
        downloads.prefetch(listOf(gate.id, other.id))
        assertEquals(OnlineDownloadState.FAILED, downloads.weekStates.value[gate.id to day])
        assertEquals(listOf(full), saved[other.id])
    }

    @Test fun reconnectAllowsTransientStorageFailureToRecover() = runBlocking {
        var attempts = 0
        val downloads = service {
            if (++attempts == 1) throw IllegalStateException("temporary storage failure")
            blocks = listOf(full)
        }
        assertFalse(downloads.fetch(gate.id, day))
        assertFalse(downloads.fetch(gate.id, day))
        downloads.reconnected()
        assertNull(downloads.weekStates.value[gate.id to day])
        assertTrue(downloads.fetch(gate.id, day))
        assertEquals(2, attempts)
    }

    @Test fun incompleteResponseNeverClaimsCoverage() = runBlocking {
        var attempts = 0
        val downloads = service { attempts++; blocks = listOf(full.copy(start = now)) }
        assertFalse(downloads.fetch(gate.id, day))
        assertEquals(OnlineDownloadState.FAILED, downloads.weekStates.value[gate.id to day])
        downloads.reconnected()
        assertFalse(downloads.fetch(gate.id, day))
        assertEquals(1, attempts)
    }
}
