package io.openwaters.slackwater.android.alerts

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.openwaters.slackwater.android.data.DeliveryPlanStore
import java.time.Instant

internal fun retainsScheduledAlarms(action: String?) =
    action == Intent.ACTION_TIMEZONE_CHANGED || action == Intent.ACTION_TIME_CHANGED ||
        action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val app = context.applicationContext
                background {
                    val access = ReminderAccessStore.get(app).current()
                    DeliveryPlanStore.get(app).refill(emptySet(), Instant.now(), access, keepDueBroadcasts = retainsScheduledAlarms(intent.action))
                    AlarmScheduler.get(app).syncLatest()
                }
            }
        }
    }
}
