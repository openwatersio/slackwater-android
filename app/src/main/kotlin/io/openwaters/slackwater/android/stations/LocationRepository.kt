package io.openwaters.slackwater.android.stations

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.LocationFix
import io.openwaters.slackwater.android.core.station.LocationInputs
import io.openwaters.slackwater.android.core.station.LocationPermission
import io.openwaters.slackwater.android.core.station.LocationSlot
import io.openwaters.slackwater.android.core.station.locationSlot
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Where fixes come from: the platform's location providers, or a fake in tests. */
interface FixSource {
    fun permission(): LocationPermission

    /** Every provider's last known position, without asking for a new one. */
    fun lastKnown(): List<LocationFix>

    /** One new position, or null when no provider is on or none answers. */
    suspend fun current(): LocationFix?
}

/**
 * The list's location state. [refresh] runs whenever the list appears, whenever the app returns to the foreground,
 * and on the list's 60-second card tick while it is on screen, so a fix that ages past the reuse limit is replaced
 * instead of leaving the spinner up with nothing asking. It shows a usable cached fix at once, then asks for a new
 * one. It never raises the permission prompt; the screen does that once, and reports it through [promptShown] and
 * [promptAnswered].
 */
class LocationRepository internal constructor(
    private val source: FixSource,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _inputs = MutableStateFlow(LocationInputs(source.permission()))
    private val requests = AtomicInteger()

    /** Everything [locationSlot] needs except the time, which ages a fix while nothing else changes. */
    val inputs: StateFlow<LocationInputs> = _inputs.asStateFlow()

    /** Reads the grant afresh, so a change made in Settings shows before the next [refresh]. */
    fun slot(): LocationSlot {
        syncPermission(source.permission())
        return locationSlot(_inputs.value, elapsedRealtimeMillis())
    }

    fun promptShown() = _inputs.update { it.copy(promptShowing = true) }

    /**
     * [refused] is the screen's reading of the answer: not granted, with the platform's rationale flag false, so the
     * system will not show the prompt again. A grant clears it.
     */
    fun promptAnswered(refused: Boolean) {
        val permission = source.permission()
        syncPermission(permission)
        _inputs.update {
            it.copy(promptShowing = false, promptRefused = refused && permission == LocationPermission.NOT_GRANTED)
        }
    }

    suspend fun refresh() {
        val permission = source.permission()
        syncPermission(permission)
        if (permission == LocationPermission.NOT_GRANTED) return
        // A retry shows the spinner again rather than the last attempt's "Location unavailable".
        _inputs.update { if (it.permission == permission) it.copy(requestFailed = false) else it }
        requests.incrementAndGet()
        var completed = false
        try {
            offer(source.lastKnown(), permission)
            offer(listOfNotNull(source.current()), permission)
            completed = true
        } finally {
            // Only the last request in flight, run to its end under the same grant, may conclude that none succeeded;
            // a cancelled one (the list left the screen) is not a failure. The count changes inside the state update,
            // so a request starting meanwhile is seen.
            _inputs.update { inputs ->
                val last = requests.decrementAndGet() == 0
                if (last && completed && inputs.permission == permission) {
                    inputs.copy(requestFailed = inputs.fix?.isUsable(elapsedRealtimeMillis()) != true)
                } else inputs
            }
        }
    }

    // A changed grant drops the held fix: one taken under approximate permission must not read as precise.
    private fun syncPermission(permission: LocationPermission) = _inputs.update {
        if (permission == it.permission) it
        else it.copy(
            permission = permission,
            fix = null,
            requestFailed = false,
            promptRefused = it.promptRefused && permission == LocationPermission.NOT_GRANTED,
        )
    }

    // Overlapping refreshes (the list appearing as the app returns to the foreground) each offer what they found;
    // the newest usable fix taken under the current permission wins, so a slow, older answer never replaces it.
    private fun offer(fixes: List<LocationFix>, permission: LocationPermission) {
        val now = elapsedRealtimeMillis()
        _inputs.update { inputs ->
            if (inputs.permission != permission) return@update inputs
            val best = (fixes + listOfNotNull(inputs.fix)).filter { it.isUsable(now) }.maxByOrNull { it.elapsedRealtimeMillis }
            inputs.copy(fix = best, requestFailed = if (best != null) false else inputs.requestFailed)
        }
    }

    companion object {
        private var shared: LocationRepository? = null

        @Synchronized
        fun get(context: Context): LocationRepository =
            shared ?: LocationRepository(PlatformFixSource(context.applicationContext)).also { shared = it }
    }
}

/** The framework's providers, preferring the fused provider where the platform has one. No Play services. */
internal class PlatformFixSource(
    private val context: Context,
    private val providerTimeoutMillis: Long = PROVIDER_TIMEOUT_MILLIS,
) : FixSource {
    private val manager = context.getSystemService(LocationManager::class.java)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun permission() = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationPermission.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPermission.APPROXIMATE
        else -> LocationPermission.NOT_GRANTED
    }

    // Each provider is read on its own: with approximate access the GPS and passive providers throw, and that must
    // not discard the network and fused fixes.
    @SuppressLint("MissingPermission")
    override fun lastKnown(): List<LocationFix> = providers(includePassive = true).mapNotNull { provider ->
        try {
            manager.getLastKnownLocation(provider)?.toFix()
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Asks every eligible provider at once and takes the first usable answer, so a silent network provider does not
     * eat into the time a cold GPS fix needs on an offline device.
     */
    override suspend fun current(): LocationFix? = withTimeoutOrNull(providerTimeoutMillis) {
        val providers = providers()
        if (providers.isEmpty()) return@withTimeoutOrNull null
        channelFlow {
            for (provider in providers) launch { send(current(provider)) }
        }.firstOrNull { it != null && it.isUsable(SystemClock.elapsedRealtime()) }
    }

    // Updates rather than getCurrentLocation, whose platform and compat forms both stop after 30 s: a cold GPS fix
    // without network assistance can take longer, and the caller's own timeout ends this request instead.
    @SuppressLint("MissingPermission")
    private suspend fun current(provider: String): LocationFix? = suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                LocationManagerCompat.removeUpdates(manager, this)
                if (continuation.isActive) continuation.resume(location.toFix())
            }
        }
        continuation.invokeOnCancellation { LocationManagerCompat.removeUpdates(manager, listener) }
        try {
            val request = LocationRequestCompat.Builder(0)
                .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
                .build()
            LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(context), listener)
        } catch (_: SecurityException) {
            continuation.resume(null)
        } catch (_: IllegalArgumentException) {
            // The provider went away between listing and asking.
            continuation.resume(null)
        }
    }

    // GPS needs precise access; with approximate access fused and network still answer.
    private fun providers(includePassive: Boolean = false): List<String> {
        val permission = permission()
        if (permission == LocationPermission.NOT_GRANTED) return emptyList()
        val enabled = manager.getProviders(true)
        val precise = permission == LocationPermission.PRECISE
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
            if (precise && includePassive) add(LocationManager.PASSIVE_PROVIDER)
        }.filter { it in enabled }
    }

    companion object {
        /** Long enough for a cold GPS fix without network assistance. */
        const val PROVIDER_TIMEOUT_MILLIS = 45_000L
    }
}

internal fun Location.toFix() = LocationFix(
    Coordinate(latitude, longitude),
    accuracyMetres = if (hasAccuracy()) accuracy.toDouble() else null,
    elapsedRealtimeMillis = elapsedRealtimeNanos / 1_000_000,
)
