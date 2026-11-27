package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant

enum class CalendarOrigin { PURCHASED, FREE_DAY }

data class StationSubscription(
    val stationId: StationId,
    val calendarId: Long? = null,
    val origin: CalendarOrigin,
    val trialEnd: Instant? = null,
    val pendingDeletion: Boolean = false,
    val pendingWork: Boolean = true,
    val publishedSource: String? = null,
) {
    fun publishes(access: ReminderEntitlement, now: Instant): Boolean =
        !pendingDeletion && (origin == CalendarOrigin.PURCHASED || access.allows(now))
}

data class PredictionInvalidation(val stationId: StationId, val revision: String)

fun migrateCalendarSubscriptions(
    subscriptions: List<StationSubscription>, successors: Map<StationId, StationId>,
): List<StationSubscription> {
    val rows = subscriptions.associateByTo(linkedMapOf()) { it.stationId }
    for (sub in subscriptions) {
        val target = successors[sub.stationId] ?: continue
        if (sub.pendingDeletion || target == sub.stationId) continue
        val existing = rows[target]
        if (existing?.pendingDeletion == true) {
            rows[sub.stationId] = sub.copy(pendingWork = true)
            continue
        }
        val replacement = if (existing == null) {
            StationSubscription(target, origin = sub.origin, trialEnd = sub.trialEnd)
        } else if (sub.origin == CalendarOrigin.PURCHASED && existing.origin != CalendarOrigin.PURCHASED) {
            existing.copy(origin = CalendarOrigin.PURCHASED, trialEnd = null, pendingWork = true)
        } else if (existing.origin == CalendarOrigin.FREE_DAY && sub.trialEnd != null &&
            (existing.trialEnd == null || sub.trialEnd > existing.trialEnd)) {
            existing.copy(trialEnd = sub.trialEnd, pendingWork = true)
        } else existing
        rows[target] = replacement
        rows[sub.stationId] = sub.copy(pendingDeletion = true, pendingWork = true)
    }
    return rows.values.toList()
}
