package io.openwaters.slackwater.android.map

import io.openwaters.slackwater.android.core.map.ChartPackSpec
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.tan

internal const val CHART_TILE_LIMIT = 200_000

// The Fiord budget counts XYZ locations once across overlapping regions and
// style sources. Storage and progress use MapLibre’s actual resource sizes.
internal fun deferredChartKeys(
    wanted: Collection<ChartPackSpec>, complete: Set<String>, limit: Int = CHART_TILE_LIMIT, underway: Set<String> = emptySet(),
): Set<String> {
    val rows = mutableMapOf<Pair<Int, Int>, BitSet>()
    val deferred = mutableSetOf<String>()
    var used = 0L
    val ordered = wanted.sortedWith(compareBy<ChartPackSpec> { it.key !in complete }.thenBy { it.key !in underway }.thenBy { it.minZoom }.thenBy { it.key })
    for (spec in ordered) {
        val boxes = (spec.minZoom..spec.maxZoom).map { tileBox(spec, it) }
        var extra = 0L
        val saved = spec.key in complete
        for (box in boxes) {
            for (y in box.top..box.bottom) {
                val row = rows[box.zoom to y]
                var x = box.left
                while (x <= box.right) {
                    val start = row?.nextClearBit(x) ?: x
                    if (start > box.right) break
                    val end = row?.nextSetBit(start)?.takeIf { it >= 0 } ?: (box.right + 1)
                    extra += minOf(end, box.right + 1) - start
                    x = end
                }
                if (!saved && extra > 0 && used + extra > limit) break
            }
            if (!saved && extra > 0 && used + extra > limit) break
        }
        if (!saved && extra > 0 && used + extra > limit) {
            deferred += spec.key
            continue
        }
        used += extra
        for (box in boxes) for (y in box.top..box.bottom) {
            rows.getOrPut(box.zoom to y) { BitSet() }.set(box.left, box.right + 1)
        }
    }
    return deferred
}

private data class TileBox(val zoom: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)

private fun tileBox(spec: ChartPackSpec, zoom: Int): TileBox {
    val width = 1 shl zoom
    fun x(longitude: Double) = (longitude + 180) / 360 * width
    fun y(latitude: Double) = (1 - asinh(tan(Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878)))) / PI) / 2 * width
    // Inverting a Mercator grid edge can leave it a few ULPs off an integer.
    fun snap(value: Double) = if (kotlin.math.abs(round(value) - value) < 1e-9) round(value) else value
    fun first(value: Double) = floor(snap(value)).toInt().coerceIn(0, width - 1)
    fun last(value: Double) = (ceil(snap(value)).toInt() - 1).coerceIn(0, width - 1)
    return TileBox(zoom, first(x(spec.west)), first(y(spec.north)), last(x(spec.east)), last(y(spec.south)))
}
