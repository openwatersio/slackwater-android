package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationResolution
import java.time.Instant
import java.util.UUID

sealed interface AlertTrigger {
    data object SlackWindowOpens : AlertTrigger
    data object Slack : AlertTrigger
    data class CurrentPeak(val flood: Boolean) : AlertTrigger
    data class TideExtreme(val high: Boolean, val ofDay: Boolean = false) : AlertTrigger
    data class TideCrossing(val heightM: Double, val rising: Boolean) : AlertTrigger {
        init { require(heightM.isFinite()) }
    }
    data object Eclipse : AlertTrigger
}

data class AlertRule(
    val id: UUID = UUID.randomUUID(),
    val stationId: StationId,
    val trigger: AlertTrigger,
    val once: Instant? = null,
    val leadSeconds: Long = 0,
    val sun: SunWindow? = null,
    val enabled: Boolean = true,
    val heightNeedsReview: Boolean = false,
) {
    init { require(leadSeconds >= 0) }

    fun isExpired(now: Instant): Boolean {
        val selected = once ?: return false
        return if (leadSeconds == 0L) selected < now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) else selected < now
    }

    fun onSuccessor(successor: StationResolution.Successor): AlertRule {
        require(stationId == successor.savedId)
        val moved = copy(stationId = successor.id)
        if (trigger !is AlertTrigger.TideCrossing) return moved
        val shift = successor.datumShiftMetres
            ?: return moved.copy(enabled = false, heightNeedsReview = true)
        return moved.copy(trigger = trigger.copy(heightM = trigger.heightM + shift))
    }

    companion object {
        fun forEditor(stationId: StationId, trigger: AlertTrigger, once: Instant? = null) =
            AlertRule(stationId = stationId, trigger = trigger, once = once, leadSeconds = 30 * 60)
    }
}
