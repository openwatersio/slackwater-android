package io.openwaters.slackwater.android.core.settings

import io.openwaters.slackwater.android.core.station.StationId
import kotlin.math.roundToInt

/** Stored deltas are in knots so adjusting the boat-wide setting moves station limits with it. */
data class ReadingSettings(val globalKnots: Double = 0.5, val deltas: Map<StationId, Double> = emptyMap()) {
    init { require(globalKnots.isFinite() && globalKnots in 0.0..10.0 && deltas.values.all { it.isFinite() }) }
    fun effectiveThresholdKn(id: StationId): Double =
        if (globalKnots == 0.0) 0.0 else (((globalKnots + (deltas[id] ?: 0.0)).coerceIn(0.0, 10.0) * 10).roundToInt()) / 10.0
}
