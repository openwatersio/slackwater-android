package io.openwaters.slackwater.android.map

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.OnlineGateDownloads
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DownloadNetworkCallbackTest {
    @Test fun handoffKeepsRunningTransferAndRealLossPublishesOffline() = runBlocking {
        val wifi = Network.fromNetworkHandle((100L shl 32) or 0xcafed00dL)
        val cellular = Network.fromNetworkHandle((101L shl 32) or 0xcafed00dL)
        val internet = NetworkCapabilities()
        val updates = CopyOnWriteArrayList<Boolean>()
        val disconnected = CountDownLatch(1)
        val online = MutableStateFlow(false)
        val station = StationIdentity(StationId("chs-test"), "Gate", "", 0.0, 0.0, StationKind.CanadianCurrent, timezone = "UTC")
        val now = Instant.parse("2026-11-07T00:00:00Z")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        var transfers = 0
        val downloads = OnlineGateDownloads({ station }, {
            if (saved.isCompleted) listOf(TimeSpan(now.minusSeconds(2*86400L), now.plusSeconds(30*86400L))) else emptyList()
        }, { _, _, _ ->
            transfers++
            started.complete(Unit)
            finish.await()
            saved.complete(Unit)
        }, online, MutableStateFlow(false), { now })
        val callback = DownloadNetworkCallback(wifi, internet, Handler(Looper.getMainLooper())) { caps, _ ->
            online.value = caps != null
            updates += online.value
            if (!online.value) disconnected.countDown()
        }
        val monitoring = launch {
            online.collectLatest { if (it) downloads.fetch(station.id, LocalDate.parse("2026-11-07")) }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { callback.refresh() }
        withTimeout(5_000) { started.await() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            callback.onLost(wifi)
            callback.refresh()
            callback.onAvailable(cellular)
            callback.onBlockedStatusChanged(cellular, false)
            callback.refresh()
            callback.onLost(wifi)
            callback.onCapabilitiesChanged(cellular, internet)
            assertEquals(listOf(true, true), updates)
        }
        finish.complete(Unit)
        withTimeout(5_000) { saved.await() }
        assertEquals(1, transfers)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { callback.onLost(cellular) }
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(true, true, false), updates)
        monitoring.cancelAndJoin()
    }
}
