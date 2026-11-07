package io.openwaters.slackwater.android.core.map

import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationIdentity
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

data class ChartPackSpec(
    val key: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val minZoom: Int,
    val maxZoom: Int,
)

private object ChartGrid {
    const val maxLatitude = 85.0511
    const val zoom = 5
    const val width = 1 shl zoom

    data class Cell(val x: Int, val y: Int) {
        fun pack(): ChartPackSpec {
            fun latitude(row: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * row / width))))
            fun longitude(column: Int) = column.toDouble() / width * 360 - 180
            return ChartPackSpec("area/$zoom/$x/$y", latitude(y + 1), longitude(x),
                latitude(y), longitude(x + 1), zoom, 8)
        }
    }

    fun cell(latitude: Double, longitude: Double): Cell {
        val radians = Math.toRadians(latitude.coerceIn(-maxLatitude, maxLatitude))
        val x = floor((longitude + 180) / 360 * width).toInt()
        val y = floor((1 - asinh(tan(radians)) / PI) / 2 * width).toInt()
        return Cell(x.coerceIn(0, width - 1), y.coerceIn(0, width - 1))
    }

    fun neighborhood(fix: Coordinate): Set<Cell> = buildSet {
        val center = cell(fix.latitude, fix.longitude)
        for (dx in -1..1) {
            for (dy in -1..1) {
                add(Cell((center.x + dx + width) % width, (center.y + dy).coerceIn(0, width - 1)))
            }
        }
    }
}

fun desiredChartPacks(fix: Coordinate?, stations: Collection<StationIdentity>): Set<ChartPackSpec> = buildSet {
    val limit = ChartGrid.maxLatitude
    add(ChartPackSpec("world", -limit, -180.0, limit, 180.0, 0, 4))
    fix?.let { ChartGrid.neighborhood(it).mapTo(this) { cell -> cell.pack() } }
    for (station in stations) {
        add(ChartGrid.cell(station.latitude, station.longitude).pack())
        val latitudeDelta = 20.0 / 111.0
        val longitudeDelta = latitudeDelta / cos(Math.toRadians(station.latitude)).coerceAtLeast(0.2)
        val south = (station.latitude - latitudeDelta).coerceIn(-limit, limit)
        val north = (station.latitude + latitudeDelta).coerceIn(-limit, limit)
        if (south >= north) continue
        val west = station.longitude - longitudeDelta
        val east = station.longitude + longitudeDelta
        val key = "station/${station.id.value}"
        fun detail(w: Double, e: Double, suffix: String = "") = ChartPackSpec(key + suffix, south, w, north, e, 9, 12)
        when {
            west < -180 -> {
                add(detail(-180.0, east))
                add(detail(west + 360, 180.0, "/wrap"))
            }
            east > 180 -> {
                add(detail(west, 180.0))
                add(detail(-180.0, east - 360, "/wrap"))
            }
            else -> add(detail(west, east))
        }
    }
}
