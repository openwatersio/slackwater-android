package io.openwaters.slackwater.android.core.map

import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

const val PIN_READING_MIN_ZOOM = 9.5
const val PIN_ZOOM_SLACK = 0.15
private const val CELL_POINTS = 44.0

data class PinBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    private val span get() = if (east < west) east - west + 360 else east - west

    fun contains(lat: Double, lon: Double): Boolean = lat in south..north &&
        (if (west <= east) lon in west..east else lon >= west || lon <= east)

    fun padded(fraction: Double = 0.5): PinBox {
        val latPad = (north - south) * fraction
        val lonPad = span * fraction
        val world = span + 2 * lonPad >= 360
        return PinBox((south - latPad).coerceAtLeast(-90.0), if (world) -180.0 else wrap(west - lonPad),
            (north + latPad).coerceAtMost(90.0), if (world) 180.0 else wrap(east + lonPad))
    }

    fun covers(inner: PinBox): Boolean {
        val start = ((inner.west - west) % 360 + 360) % 360
        return inner.south >= south && inner.north <= north && (span >= 360 || start + inner.span <= span)
    }

    private fun wrap(lon: Double): Double {
        val value = lon % 360
        return when {
            value > 180 -> value - 360
            value < -180 -> value + 360
            else -> value
        }
    }
}

data class PinCandidate(val station: StationIdentity, val rank: Double)

fun pinRank(subordinate: Boolean, signal: Double): Double =
    (if (subordinate) 10 else 0) + (9 - signal).coerceAtLeast(0.0)

fun visibleStations(box: PinBox, zoom: Double, candidates: List<PinCandidate>,
                    pinned: StationId? = null, decimate: Boolean = true): List<PinCandidate> {
    val visible = candidates.asSequence().filter { box.contains(it.station.latitude, it.station.longitude) }
    if (!decimate) return visible.toList()
    val cellLon = CELL_POINTS * 360 / (512 * 2.0.pow(zoom))
    val cellLat = (cellLon * cos(Math.toRadians((box.north + box.south) / 2))).coerceAtLeast(1e-9)
    val winners = mutableMapOf<Pair<Long, Long>, PinCandidate>()
    for (candidate in visible) {
        val cell = floor(candidate.station.longitude / cellLon).toLong() to
            floor(candidate.station.latitude / cellLat).toLong()
        val held = winners[cell]
        if (held == null || candidate.rank < held.rank) winners[cell] = candidate
    }
    val result = winners.values.toMutableList()
    if (pinned != null && result.none { it.station.id == pinned }) {
        candidates.firstOrNull { it.station.id == pinned }?.let(result::add)
    }
    return result
}
