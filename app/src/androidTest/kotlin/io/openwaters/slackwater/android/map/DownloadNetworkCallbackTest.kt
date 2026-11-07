package io.openwaters.slackwater.android.map

import android.net.ConnectivityManager
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
    @Test fun liveEmulatorWifiToCellularKeepsGateFetchActive() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("gateHandoff") == "live")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun uses(transport: Int) = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(transport) == true
        assertTrue("Start with Wi-Fi connected", uses(NetworkCapabilities.TRANSPORT_WIFI))
        val online = MutableStateFlow(false)
        val updates = CopyOnWriteArrayList<Boolean>()
        watchDownloadNetwork(context, preserveHandoffs = true) { available, _, _ ->
            updates += available
            online.value = available
        }
        val fetch = GateFetch(online)
        val job = launch { fetch.observe() }
        try {
            withTimeout(10_000) { fetch.started.await() }
            val index = updates.size
            instrumentation.uiAutomation.executeShellCommand("svc wifi disable").close()
            withTimeout(20_000) { while (!uses(NetworkCapabilities.TRANSPORT_CELLULAR)) delay(100) }
            delay(1_500)
            assertFalse(updates.drop(index).contains(false))
            fetch.finish.complete(Unit)
            withTimeout(5_000) { fetch.saved.await() }
            assertEquals(1, fetch.transfers)
        } finally {
            instrumentation.uiAutomation.executeShellCommand("svc wifi enable").close()
            job.cancelAndJoin()
        }
    }

    @Test fun handoffKeepsRunningTransferAndRealLossPublishesOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val original = requireNotNull(connectivity.activeNetwork)
        val replacement = connectivity.allNetworks.firstOrNull { it != original } ?: original
        val internet = requireNotNull(connectivity.getNetworkCapabilities(original))
        assertTrue(internet.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        val updates = CopyOnWriteArrayList<Boolean>()
        val disconnected = CountDownLatch(1)
        val online = MutableStateFlow(false)
        val fetch = GateFetch(online)
        val callback = DownloadNetworkCallback(original, internet, Handler(Looper.getMainLooper()), preserveHandoffs = true) { caps, _ ->
            online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            updates += online.value
            if (!online.value) disconnected.countDown()
        }
        val monitoring = launch { fetch.observe() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { callback.refresh() }
        withTimeout(5_000) { fetch.started.await() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            callback.onLost(original)
            callback.refresh()
            callback.onAvailable(replacement)
            callback.onBlockedStatusChanged(replacement, false)
            callback.refresh()
            if (original != replacement) callback.onLost(original)
            callback.onCapabilitiesChanged(replacement, internet)
            assertEquals(listOf(true, true), updates)
        }
        fetch.finish.complete(Unit)
        withTimeout(5_000) { fetch.saved.await() }
        assertEquals(1, fetch.transfers)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { callback.onLost(replacement) }
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(true, true, false), updates)
        monitoring.cancelAndJoin()
    }

    private class GateFetch(private val online: MutableStateFlow<Boolean>) {
        private val station = StationIdentity(StationId("chs-test"), "Gate", "", 0.0, 0.0, StationKind.CanadianCurrent, timezone = "UTC")
        private val now = Instant.parse("2026-11-07T00:00:00Z")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        var transfers = 0
        private val downloads = OnlineGateDownloads({ station }, {
            if (saved.isCompleted) listOf(TimeSpan(now.minusSeconds(2 * 86400L), now.plusSeconds(30 * 86400L))) else emptyList()
        }, { _, _, _ ->
            transfers++
            started.complete(Unit)
            finish.await()
            saved.complete(Unit)
        }, online, MutableStateFlow(false), { now })

        suspend fun observe() = online.collectLatest { if (it) downloads.fetch(station.id, LocalDate.parse("2026-11-07")) }
    }
}
