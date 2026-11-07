package io.openwaters.slackwater.android.map

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** The process-wide pack manager can keep downloading after its activity stops. */
internal fun watchChartDataSaver(context: Context, update: (Boolean) -> Unit) {
    watchDownloadNetwork(context) { _, restricted, _ -> update(restricted) }
}

internal fun watchDownloadNetwork(context: Context, update: (online: Boolean, restricted: Boolean, validated: Boolean) -> Unit) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
    val main = Handler(Looper.getMainLooper())
    val initial = connectivity.activeNetwork
    val callback = DownloadNetworkCallback(initial, connectivity.getNetworkCapabilities(initial), main) { capabilities, blocked ->
        val unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val restricted = connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED &&
            (blocked || !unmetered)
        val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        update(internet && (!blocked || restricted), restricted,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true && !blocked)
    }
    connectivity.registerDefaultNetworkCallback(callback, main)
    ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = callback.refresh()
    }, IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    callback.refresh()
}

internal class DownloadNetworkCallback(
    private var network: Network?,
    private var capabilities: NetworkCapabilities?,
    private val main: Handler,
    private val publish: (NetworkCapabilities?, Boolean) -> Unit,
) : ConnectivityManager.NetworkCallback() {
    private var blocked = false
    private var awaitingCapabilities = false
    private var confirmingLoss = false
    private val loss = Runnable {
        confirmingLoss = false
        refresh()
    }

    fun refresh() {
        if (!awaitingCapabilities && !confirmingLoss) publish(capabilities, blocked)
    }

    override fun onAvailable(available: Network) {
        main.removeCallbacks(loss)
        confirmingLoss = false
        network = available
        awaitingCapabilities = true
        blocked = false
    }

    override fun onCapabilitiesChanged(changed: Network, current: NetworkCapabilities) {
        if (network != changed) return
        capabilities = current
        awaitingCapabilities = false
        refresh()
    }

    override fun onBlockedStatusChanged(changed: Network, isBlocked: Boolean) {
        if (network != changed) return
        blocked = isBlocked
        refresh()
    }

    override fun onLost(lost: Network) {
        if (network != lost) return
        network = null
        capabilities = null
        blocked = false
        awaitingCapabilities = false
        // A replacement default network can arrive immediately after the old network is lost.
        confirmingLoss = true
        main.postDelayed(loss, 1_000)
    }
}
