package io.openwaters.slackwater.android.core.map

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.*

data class MapPinState(val state: String = "unknown", val reading: String? = null,
                       val bearing: Double? = null, val gauge: Int? = null)

private const val HALF_HOUR = 1800L
private val speeds = mapOf("M2" to 28.9841, "S2" to 30.0, "N2" to 28.4397, "K2" to 30.0821,
    "K1" to 15.0411, "O1" to 13.9430, "P1" to 14.9589, "Q1" to 13.3987)

fun tideRangeProxy(amplitudes: Map<String, Double>): Double =
    amplitudes.entries.sumOf { (name, amplitude) -> amplitude * (speeds[name] ?: 0.0) }

fun tidePinState(now: Instant, detailed: Boolean, rangeProxy: Double,
                 heights: (Instant, Instant, Double) -> List<TidePoint>,
                 extremes: (Instant, Instant) -> List<TideExtreme>, formatHeight: (Double) -> String): MapPinState {
    // Engine steps snap to the grid. The first two heights straddle now.
    val delta = if (rangeProxy > 0) {
        val pair = heights(now, now.plusSeconds(HALF_HOUR), HALF_HOUR.toDouble())
        pair.getOrNull(1)?.let { it.heightMetres - pair.first().heightMetres }
    } else null
    val rising = if (delta != null && abs(delta) >= 0.01 && abs(delta) / rangeProxy >= 0.0004) delta > 0
        else extremes(now, now.plusSeconds(30 * 3600)).firstOrNull { it.time > now }
            ?.let { it.kind == ExtremeKind.HIGH } ?: return MapPinState()
    val height = if (detailed) heights(now, now.plusSeconds(1), 1.0).firstOrNull()?.heightMetres else null
    val turns = if (height != null) extremes(now.minusSeconds(15 * 3600), now.plusSeconds(15 * 3600)) else emptyList()
    return tidePinReadout(rising, height, turns, now, formatHeight)
}

fun tidePinReadout(rising: Boolean, height: Double?, turns: List<TideExtreme>, now: Instant,
                   formatHeight: (Double) -> String): MapPinState {
    val state = if (rising) "rising" else "falling"
    if (height == null) return MapPinState(state)
    val prev = turns.lastOrNull { it.time <= now }
    val next = turns.firstOrNull { it.time > now }
    val gauge = if (prev != null && next != null && prev.kind != next.kind &&
        (next.kind == ExtremeKind.HIGH) == rising && abs(next.heightMetres - prev.heightMetres) > 0.01) {
        val low = min(prev.heightMetres, next.heightMetres)
        (((height - low) / abs(next.heightMetres - prev.heightMetres)).coerceIn(0.0, 1.0) * 8).roundToInt()
    } else null
    return MapPinState(state, formatHeight(height), gauge = gauge)
}

private val rampAnchors = listOf(3.0, 8.0, 12.0)
private val rampColors = listOf(0xF5C96B, 0xE8763C, 0xC93A32)

private fun rampColor(speed: Double): String {
    val v = speed.coerceIn(rampAnchors.first(), rampAnchors.last())
    val hi = if (v <= rampAnchors[1]) 1 else 2
    val f = (v - rampAnchors[hi - 1]) / (rampAnchors[hi] - rampAnchors[hi - 1])
    fun channel(shift: Int): Int {
        val a = (rampColors[hi - 1] shr shift) and 255
        val b = (rampColors[hi] shr shift) and 255
        val c = a + f * (b - a)
        return (c + (255 - c) * 0.3).roundToInt()
    }
    return String.format(Locale.ROOT, "#%02x%02x%02x", channel(16), channel(8), channel(0))
}

// The comfort threshold controls the window. Only the zero instant is green.
@Suppress("UNUSED_PARAMETER")
fun currentPinState(signed: Double?, floodBearing: Double?, ebbBearing: Double?, thresholdKnots: Double,
                    detailed: Boolean, formatSpeed: (Double) -> String): MapPinState {
    if (signed == null || !signed.isFinite()) return MapPinState()
    return MapPinState(if (signed == 0.0) "#88b868" else rampColor(abs(signed)),
        if (detailed) formatSpeed(abs(signed)) else null, if (signed >= 0) floodBearing else ebbBearing)
}

class ReferenceCurrentEvents {
    private var bucket: Long? = null
    private val events = mutableMapOf<StationId, List<CurrentEvent>>()

    @Synchronized
    fun events(id: StationId, now: Instant, load: (Instant, Instant) -> List<CurrentEvent>): List<CurrentEvent> {
        val next = Math.floorDiv(now.epochSecond, HALF_HOUR)
        if (next != bucket) {
            events.clear()
            bucket = next
        }
        val start = Instant.ofEpochSecond(next * HALF_HOUR)
        return events.getOrPut(id) { load(start.minusSeconds(17 * 3600), start.plusSeconds(HALF_HOUR + 17 * 3600)) }
    }
}

// Engine 0.1's reduction is internal. Apply its offsets to the shared reference
// events, then evaluate the half-cosine between the nearest reduced events.
fun subordinateCurrentSpeed(now: Instant, referenceEvents: List<CurrentEvent>, station: SubordinateCurrentStation): Double? {
    var nextPhase: CurrentEventKind? = null
    var before: Pair<Instant, Double>? = null
    var after: Pair<Instant, Double>? = null
    for (event in referenceEvents.asReversed()) {
        val (offset, speed) = when (event.kind) {
            CurrentEventKind.MAX_FLOOD -> station.floodTimeOffsetSeconds to event.speedKnots * station.floodSpeedRatio
            CurrentEventKind.MAX_EBB -> station.ebbTimeOffsetSeconds to event.speedKnots * station.ebbSpeedRatio
            CurrentEventKind.SLACK -> when (nextPhase) {
                CurrentEventKind.MAX_FLOOD -> station.slackBeforeFloodOffsetSeconds to 0.0
                CurrentEventKind.MAX_EBB -> station.slackBeforeEbbOffsetSeconds to 0.0
                else -> continue
            }
        }
        if (event.kind != CurrentEventKind.SLACK) nextPhase = event.kind
        val time = event.time.plusNanos((offset * 1e9).roundToLong())
        if (time == now) return speed
        if (time < now && (before == null || time > before.first)) before = time to speed
        if (time > now && (after == null || time < after.first)) after = time to speed
    }
    val left = before ?: return null
    val right = after ?: return null
    val fraction = Duration.between(left.first, now).toNanos().toDouble() /
        Duration.between(left.first, right.first).toNanos()
    return left.second + (right.second - left.second) * (1 - cos(PI * fraction)) / 2
}

fun doddMapVector(signed: Double?, floodBearing: Double?, ebbBearing: Double?, now: Instant): CurrentVector? {
    if (signed == null || !signed.isFinite()) return null
    return listOf(CurrentPoint(now, signed)).vectorAt(now, floodBearing, ebbBearing)
        ?.takeIf { it.directionDegrees != null }
}
