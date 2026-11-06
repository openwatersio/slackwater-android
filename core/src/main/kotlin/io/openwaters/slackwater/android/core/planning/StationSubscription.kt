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
