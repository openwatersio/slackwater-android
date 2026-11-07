package io.openwaters.slackwater.android.widgets

import android.content.Context
import android.content.SharedPreferences
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.BundledStationCatalog
import io.openwaters.slackwater.android.data.UserStateStore
import io.openwaters.slackwater.android.stations.PlatformFixSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal enum class NearestStation(val series: StationSeries?) {
    ANY(null), TIDE(StationSeries.Tide), CURRENT(StationSeries.Current);

    val key get() = "nearest:$name"
}

private fun Context.widgetSettings() = getSharedPreferences("widget-stations", Context.MODE_PRIVATE)

internal fun saveWidgetChoice(context: Context, widgetId: Int, choice: String) {
    context.widgetSettings().edit().putString("widget:$widgetId", choice).apply()
}

fun deleteWidgetSettings(context: Context, id: Int) {
    context.widgetSettings().edit().remove("widget:$id").apply()
}

internal fun hasNearestChoice(context: Context, id: Int): Boolean {
    val choice = context.widgetSettings().getString("widget:$id", null)
    return NearestStation.entries.any { it.key == choice }
}

@Synchronized
internal fun clearWidgetSettings(context: Context) {
    context.widgetSettings().edit().clear().apply()
}

@Synchronized
internal fun retainWidgetPermission(settings: SharedPreferences, permission: LocationPermission) {
    val editor = settings.edit()
    for (grant in listOf(LocationPermission.APPROXIMATE, LocationPermission.PRECISE)) {
        if (grant != permission) {
            for (mode in NearestStation.entries) editor.remove("${grant.name}:${mode.key}")
        }
    }
    editor.apply()
}

internal fun nearestWidgetStations(
    stations: List<StationIdentity>, coordinate: Coordinate, chosen: Set<StationId>,
): Map<NearestStation, StationId> = buildMap {
    for (mode in NearestStation.entries) {
        val nearest = stations.asSequence().filter { it.series != null && (mode.series == null || it.series == mode.series) }
            .minByOrNull { distanceKm(coordinate, it.coordinate) } ?: continue
        val preferred = stations.firstOrNull { it.id in chosen && it.place == nearest.place }
        put(mode, preferred?.id ?: nearest.id)
    }
}

/** Only station IDs are stored. The worker never asks the system for a position. */
internal suspend fun cacheWidgetStations(
    context: Context, coordinate: Coordinate?, permission: LocationPermission,
): Boolean = withContext(Dispatchers.Default) {
    val source = PlatformFixSource(context)
    val current = source.permission()
    retainWidgetPermission(context.widgetSettings(), current)
    if (coordinate == null || permission == LocationPermission.NOT_GRANTED || current != permission) {
        return@withContext false
    }
    val places = UserStateStore.get(context).places.first().getOrNull()
    val stations = BundledStationCatalog(context.assets).identities()
    val nearest = nearestWidgetStations(stations, coordinate, places?.chosenIds.orEmpty())
    storeNearestStations(context, nearest, permission)
}

@Synchronized
private fun storeNearestStations(context: Context, nearest: Map<NearestStation, StationId>, permission: LocationPermission): Boolean {
    val current = PlatformFixSource(context).permission()
    val settings = context.widgetSettings()
    retainWidgetPermission(settings, current)
    if (current != permission || !hasStationWidgets(context)) return false
    val editor = settings.edit()
    var changed = false
    for ((mode, id) in nearest) {
        val key = "${permission.name}:${mode.key}"
        if (settings.getString(key, null) != id.value) {
            editor.putString(key, id.value)
            changed = true
        }
    }
    if (changed) editor.apply()
    return changed
}

suspend fun widgetStation(context: Context, widgetId: Int): StationId? = withContext(Dispatchers.IO) {
    val settings = context.widgetSettings()
    val permission = PlatformFixSource(context).permission()
    retainWidgetPermission(settings, permission)
    val choice = settings.getString("widget:$widgetId", null) ?: return@withContext null
    val nearest = NearestStation.entries.firstOrNull { it.key == choice }
    val id = if (nearest == null) choice else {
        if (permission == LocationPermission.NOT_GRANTED) return@withContext null
        settings.getString("${permission.name}:${nearest.key}", null) ?: return@withContext null
    }
    val resolved = BundledStationCatalog(context.assets).resolve(StationId(id))
    resolved.id.takeUnless { resolved is StationResolution.Unknown }
}
