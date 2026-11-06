package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.android.core.station.StationId
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface ReminderEntitlement {
    data object Free : ReminderEntitlement
    data object Purchased : ReminderEntitlement
    data class FreeDay(val ends: Instant) : ReminderEntitlement
}

fun ReminderEntitlement.allows(fire: Instant): Boolean = when (this) {
    ReminderEntitlement.Free -> false
    ReminderEntitlement.Purchased -> true
    is ReminderEntitlement.FreeDay -> fire < ends
}

// App policy chosen for parity and reliability testing.
const val REMINDER_BUDGET = 64
val NOTIFICATION_HORIZON: Duration = Duration.ofDays(14)
val CALENDAR_HORIZON: Duration = Duration.ofDays(90)

data class DeliveryPlan(
    val horizon: Instant,
    val calendar: List<AlertOccurrence>,
    val notifications: List<AlertOccurrence>,
    val overflow: List<AlertOccurrence>,
) {
    val scheduledThrough: Map<UUID, Instant>
        get() = notifications.groupBy { it.ruleId }.mapValues { (_, entries) -> entries.maxOf { it.fire } }

    /** Background delivery can only promote entries from the saved plan. */
    fun refill(
        deliveredKeys: Set<String>,
        now: Instant,
        entitlement: ReminderEntitlement,
        keepDueBroadcasts: Boolean = false,
    ): DeliveryPlan {
        fun eligible(entry: AlertOccurrence): Boolean = entry.key !in deliveredKeys &&
            entry.fire <= horizon && entitlement.allows(maxOf(now, entry.fire))
        val pending = notifications.filter {
            eligible(it) && (it.fire >= now || ((keepDueBroadcasts || it.originalFire != null) && it.isDeliverableAt(now)))
        }
        val remaining = pending + overflow.filter { eligible(it) && it.fire >= now }
        return copy(notifications = remaining.take(REMINDER_BUDGET), overflow = remaining.drop(REMINDER_BUDGET))
    }
}

private data class ReminderIdentity(val station: StationId, val trigger: AlertTrigger, val event: Instant, val lead: Long)

fun deliveryPlan(
    rules: List<AlertRule>,
    occurrences: List<AlertOccurrence>,
    calendarOccurrences: List<AlertOccurrence>,
    now: Instant,
    entitlement: ReminderEntitlement,
    newlySavedRuleIds: Set<UUID> = emptySet(),
    previous: DeliveryPlan? = null,
    deliveredKeys: Set<String> = emptySet(),
): DeliveryPlan {
    val horizon = now.plus(NOTIFICATION_HORIZON)
    val calendarEnd = now.plus(CALENDAR_HORIZON)
    val calendar = calendarOccurrences.filter { it.event > now && it.event <= calendarEnd }.sortedBy { it.event }
    val enabled = rules.filter { it.enabled && !it.heightNeedsReview && !it.isExpired(now) }.distinctBy { it.id }.associateBy { it.id }
    val alreadyScheduled = previous?.notifications.orEmpty().associateBy { it.key }
    val notifications = occurrences.mapNotNull { occurrence ->
        val rule = enabled[occurrence.ruleId] ?: return@mapNotNull null
        if (occurrence.key in deliveredKeys || !occurrence.isDeliverableAt(now) || occurrence.fire > horizon) return@mapNotNull null
        if (occurrence.fire >= now) return@mapNotNull occurrence.takeIf { entitlement.allows(it.fire) }
        if (!entitlement.allows(now)) return@mapNotNull null

        val pending = alreadyScheduled[occurrence.key]
        // Android can still deliver a previously scheduled inexact alarm after its fire time.
        if (pending == occurrence) return@mapNotNull occurrence
        if (occurrence.event <= now) return@mapNotNull null
        val fire = when {
            pending?.originalFire == occurrence.fire -> pending.fire
            rule.id in newlySavedRuleIds -> now
            else -> return@mapNotNull null
        }
        occurrence.copy(fire = fire, originalFire = occurrence.fire)
    }.sortedWith(compareBy<AlertOccurrence> { it.fire }.thenBy { enabled.getValue(it.ruleId).once == null }
        .thenBy { it.event }.thenBy { it.ruleId })
        .distinctBy {
            val rule = enabled.getValue(it.ruleId)
            ReminderIdentity(rule.stationId, rule.trigger, it.event, rule.leadSeconds)
        }
    return DeliveryPlan(horizon, calendar, notifications.take(REMINDER_BUDGET), notifications.drop(REMINDER_BUDGET))
}
