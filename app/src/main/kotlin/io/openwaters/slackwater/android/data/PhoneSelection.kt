package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.stations.StationListState

internal data class PhoneSelection(val radiusKm: Int, val allCurrents: Boolean) {
    val backgroundRequested get() = radiusKm > 0 || allCurrents

    fun withRadius(km: Int, enabled: Boolean): PhoneSelection {
        require(km == 25 || km == 150)
        return copy(radiusKm = if (enabled) maxOf(radiusKm, km) else if (km == 150) minOf(radiusKm, 25) else 0)
    }

    fun stations(list: StationListState): List<StationId> {
        val visible = with(list.sections) { favorites + heroes.map { it.id } + nearMe.map { it.id } + recents }.toSet()
        val origin = list.slot.fixCoordinate
        return list.stations.values.asSequence().filter {
            it.kind in canadianKinds
        }.map { station ->
            station to (origin?.let { distanceKm(it, station.coordinate) } ?: Double.POSITIVE_INFINITY)
        }.filter { (station, distance) ->
            station.id in visible || (radiusKm > 0 && distance <= radiusKm) ||
                (allCurrents && station.series == StationSeries.Current)
        }.sortedBy { it.second }.map { it.first.id }.toList()
    }

    private companion object {
        val canadianKinds = setOf(StationKind.CanadianTide, StationKind.CanadianCurrent, StationKind.DerivedGate)
    }
}
