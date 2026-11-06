package io.openwaters.slackwater.android.core.timeline

import io.openwaters.almanac.*
import io.openwaters.slackwater.flowPeaks
import io.openwaters.slackwater.slackRuns
import io.openwaters.slackwater.android.core.planning.SunWindows
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import java.time.Duration
import java.time.Instant

// At 18 dp/hour, capture is strictly under 4.5 dp, animation starts at 0.5 dp,
// and measured windows must span at least 5 dp to draw their own stops.
private val captureRadius = Duration.ofMinutes(15)
private val minimumAnimation = Duration.ofSeconds(100)
private val minimumDrawnWindow = Duration.ofSeconds(1000)
private val maximumAnimation = Duration.ofDays(7)

class MagneticTargets(times: Iterable<Instant> = emptyList()) {
    private val moments = java.util.TreeSet<Instant>().apply { addAll(times) }

    fun capture(time: Instant): Instant? {
        val nearest = listOfNotNull(moments.floor(time), moments.ceiling(time))
            .minByOrNull { Duration.between(time, it).abs() } ?: return null
        return nearest.takeIf { Duration.between(time, it).abs() < captureRadius }
    }

    fun next(time: Instant): Instant? = moments.higher(time.plusSeconds(1))
    fun previous(time: Instant): Instant? = moments.lower(time.minusSeconds(1))

    companion object {
        fun from(
            window: TimelineWindow,
            series: PredictionSeries,
            thresholdKnots: Double,
            latitude: Double,
            longitude: Double,
            showSky: Boolean,
        ): MagneticTargets {
            val times = buildList {
                when (series) {
                    is PredictionSeries.Tide -> {
                        series.events.mapTo(this) { it.time }
                        flowPeaks(series.rates, 0.6).mapTo(this) { it.time }
                    }
                    is PredictionSeries.Current -> {
                        series.events.mapTo(this) { it.time }
                        for (run in slackRuns(series.points, series.events, thresholdKnots)) {
                            if (Duration.between(run.start, run.end) >= minimumDrawnWindow) {
                                add(run.start)
                                add(run.end)
                            }
                        }
                    }
                    is PredictionSeries.Derived -> series.slacks.mapTo(this) { it.time }
                }
                if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@buildList
                val observer = Observer(latitude, longitude)
                // Almanac searches half-open intervals at millisecond precision.
                val skyStart = maxOf(window.start, Instant.parse("1950-01-01T00:00:00Z"))
                val skyEnd = minOf(window.end, Instant.parse("2101-01-01T00:00:00Z").minusMillis(1)).plusMillis(1)
                if (skyStart >= skyEnd) return@buildList
                if (showSky) {
                    sunEvents(skyStart, skyEnd, observer)
                        .filter { it.kind == SunEventKind.RISE || it.kind == SunEventKind.SET }
                        .mapTo(this) { it.time }
                }
                lunarEclipses(skyStart, skyEnd)
                    .filter { SunWindows.visibleAtStation(it, observer) }.mapTo(this) { it.peak }
                solarEclipses(skyStart, skyEnd, observer).mapTo(this) { it.peak }
            }
            return MagneticTargets(times.filter { it >= window.start && it <= window.end })
        }
    }
}

fun shouldAnimateTimelineMove(
    from: Instant,
    to: Instant,
    reduceMotion: Boolean,
    alwaysDirect: Boolean = false,
    startsInCoverage: Boolean = true,
): Boolean = startsInCoverage && !reduceMotion && !alwaysDirect &&
    Duration.between(from, to).abs() in minimumAnimation..maximumAnimation
