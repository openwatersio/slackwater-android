package io.openwaters.slackwater.android.core.timeline

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

data class TimelineWindow(val start: Instant, val end: Instant) {
    init { require(end >= start) }

    val width: Double get() = x(end)
    fun x(time: Instant): Double {
        val elapsed = Duration.between(start, time)
        return (elapsed.seconds + elapsed.nano / 1e9) / SECONDS_PER_DP
    }

    fun time(x: Double): Instant {
        val seconds = x * SECONDS_PER_DP
        val whole = seconds.toLong()
        return start.plusSeconds(whole).plusNanos(((seconds - whole) * 1e9).roundToLong())
    }

    fun offset(target: Instant, viewport: Double): Double =
        (x(target) - viewport / 2).coerceIn(0.0, (width - viewport).coerceAtLeast(0.0))

    fun selected(offset: Double, viewport: Double): Instant =
        time(offset + minOf(width, viewport) / 2).coerceIn(start, end)

    fun clamp(target: Instant, viewport: Double): Instant {
        val half = minOf(width, viewport) / 2
        return target.coerceIn(time(half), time(width - half))
    }

    companion object {
        val forward = Duration.ofHours(180)
        const val SECONDS_PER_DP = 200.0
        fun fixed(anchor: LocalDate, zone: ZoneId): TimelineWindow {
            val midnight = anchor.atStartOfDay(zone).toInstant()
            return TimelineWindow(midnight.minus(Duration.ofHours(48)), midnight.plus(forward))
        }
    }
}
