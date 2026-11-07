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
        assertTrue(downloads.states.value.isEmpty())
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
        downloads.states.first { it[gate.id] == OnlineDownloadState.WAITING_TO_RETRY }
        assertTrue(withTimeout(5_000) { downloads.fetch(gate.id, day, manual = true) })
        assertFalse(automatic.await())
        assertEquals(2, tries)
    }

    @Test fun incompleteResponseNeverClaimsCoverage() = runBlocking {
        val downloads = service { blocks = listOf(full.copy(start = now)) }
        assertFalse(downloads.fetch(gate.id, day))
        assertEquals(OnlineDownloadState.FAILED, downloads.states.value[gate.id])
    }
}
