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
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
    val main = Handler(Looper.getMainLooper())
    fun refresh() {
        update(connectivity.isActiveNetworkMetered &&
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
    }
    connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        override fun onLost(network: Network) = refresh()
    }, main)
    ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }, IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    refresh()
}
