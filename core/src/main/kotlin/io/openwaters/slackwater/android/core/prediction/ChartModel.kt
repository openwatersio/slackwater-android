package io.openwaters.slackwater.android.core.prediction

import io.openwaters.slackwater.*
import java.time.*
import kotlin.math.abs

/** Keep the decoded model with the loaded prediction so scrubbing only evaluates the selected second. */
sealed interface ChartModel {
    class Tide(private val heights: (Instant, Instant, Double) -> List<TidePoint>) : ChartModel {
        fun height(at: Instant): Double? = heights(at, at, 1.0).heightAt(at)
    }

    class Current(
        private val speeds: (Instant, Instant, Double) -> List<CurrentPoint>,
        private val events: (Instant, Instant) -> List<CurrentEvent>,
    ) : ChartModel {
        fun reading(at: Instant, series: PredictionSeries.Current): CurrentReading? =
            readCurrent(
                at,
                speeds(at, at, 1.0),
                series.events,
                series.floodBearing,
                series.ebbBearing
            )

        private val years = mutableMapOf<Pair<Int, ZoneId>, Boolean>()
        @Synchronized
        fun isReach(year: Int, zone: ZoneId): Boolean = years.getOrPut(year to zone) {
            val first = LocalDate.of(year, 1, 1)
            val start = first.atStartOfDay(zone).toInstant()
            val end = first.plusYears(1).atStartOfDay(zone).toInstant().minusNanos(1)
            val maxima = events(start, end).filter { it.kind != CurrentEventKind.SLACK }
            val peak =
                maxima.maxOfOrNull { abs(it.speedKnots) } ?: speeds(start, start, 1.0).firstOrNull()
                    ?.let { abs(it.speedKnots) }
            peak != null && peak < 3.0
        }
    }
}
