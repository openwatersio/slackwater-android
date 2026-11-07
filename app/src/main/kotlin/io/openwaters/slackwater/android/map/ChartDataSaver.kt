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
    var network = connectivity.activeNetwork
    var capabilities = connectivity.getNetworkCapabilities(network)
    var blocked = false
    fun refresh() {
        // A blocked default network can be invisible to activeNetwork. Wait for
        // an unmetered capability before resuming transfers under Data Saver.
        val unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val restricted = connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED &&
            (blocked || !unmetered)
        val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        update(internet && (!blocked || restricted), restricted,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true && !blocked)
    }
    connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) {
            network = available
            capabilities = null
            blocked = false
            refresh()
        }
        override fun onCapabilitiesChanged(changed: Network, current: NetworkCapabilities) {
            if (network == changed) {
                capabilities = current
                refresh()
            }
        }
        override fun onBlockedStatusChanged(changed: Network, isBlocked: Boolean) {
            if (network == changed) {
                blocked = isBlocked
                refresh()
            }
        }
        override fun onLost(lost: Network) {
            if (network == lost) {
                network = null
                capabilities = null
                blocked = false
                refresh()
            }
        }
    }, main)
    ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }, IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    refresh()
}
