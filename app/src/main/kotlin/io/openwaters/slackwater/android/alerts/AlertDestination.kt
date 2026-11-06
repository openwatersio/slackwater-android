package io.openwaters.slackwater.android.alerts

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant

data class AlertDestination(val station: StationId, val event: Instant) {
    companion object {
        private const val ACTION = "io.openwaters.slackwater.android.OPEN_ALERT"
        fun intent(context: Context, stationId: StationId, event: Instant): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION).setData(
                Uri.Builder().scheme("slackwater").authority("alert").appendPath(stationId.value)
                    .appendQueryParameter("event", event.toString()).build()
            ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun read(intent: Intent): AlertDestination? {
            val uri = intent.data ?: return null
            if (intent.action != ACTION || uri.scheme != "slackwater" || uri.host != "alert") return null
            val station = uri.pathSegments.singleOrNull()?.takeIf(String::isNotBlank) ?: return null
            val instant = uri.getQueryParameter("event")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            if (instant < Instant.parse("1950-01-01T00:00:00Z") || instant >= Instant.parse("2101-01-01T00:00:00Z")) return null
            return AlertDestination(StationId(station), instant)
        }
    }
}
