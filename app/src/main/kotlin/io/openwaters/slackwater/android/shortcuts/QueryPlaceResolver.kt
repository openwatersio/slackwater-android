package io.openwaters.slackwater.android.shortcuts

import android.content.Context
import android.os.SystemClock
import io.openwaters.slackwater.android.core.shortcuts.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.BundledStationCatalog
import io.openwaters.slackwater.android.data.UserStateStore
import io.openwaters.slackwater.android.stations.FixSource
import io.openwaters.slackwater.android.stations.PlatformFixSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class QueryPlaceResolver(context: Context) {
    private val catalog = BundledStationCatalog(context.applicationContext.assets)
    private val fixes = PlatformFixSource(context.applicationContext)
    private val saved = UserStateStore.get(context)

    suspend fun resolve(place: QueryPlace, series: StationSeries): QueryPlaces = withContext(Dispatchers.IO) {
        when (place) {
            is QueryPlace.Selected -> {
                val resolution = catalog.resolve(place.id)
                val target = (resolution as? StationResolution.Successor)?.target ?: resolution
                selectedQueryPlace((target as? StationResolution.Live)?.identity, series)
            }
            QueryPlace.Nearby -> nearbyQueryPlaces(catalog.identities(), series, queryFix(fixes, refresh = true))
            is QueryPlace.Named -> {
                val preferences = saved.places.first().getOrThrow()
                namedQueryPlaces(catalog.identities(), series, place.text, queryFix(fixes, refresh = false),
                    preferences.favorites, preferences.recents, preferences.chosenIds)
            }
        }
    }
}

internal suspend fun queryFix(
    source: FixSource,
    refresh: Boolean,
    now: () -> Long = SystemClock::elapsedRealtime,
    timeoutMillis: Long = 5_000,
): Coordinate? {
    val permission = source.permission()
    if (permission == LocationPermission.NOT_GRANTED) return null
    fun usable(fix: LocationFix) = fix.isUsable(now()) &&
        fix.coordinate.latitude in -90.0..90.0 && fix.coordinate.longitude in -180.0..180.0
    val cached = source.lastKnown().filter(::usable).maxByOrNull { it.elapsedRealtimeMillis }
    val fix = cached ?: if (refresh) withTimeoutOrNull(timeoutMillis) { source.current() } else null
    return fix?.takeIf { usable(it) && source.permission() == permission }?.coordinate
}
