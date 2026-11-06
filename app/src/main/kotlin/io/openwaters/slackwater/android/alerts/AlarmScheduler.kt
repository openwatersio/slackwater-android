package io.openwaters.slackwater.android.alerts

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import io.openwaters.slackwater.android.core.planning.DeliveryPlan
import io.openwaters.slackwater.android.data.DeliveryPlanStore
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AlarmAccess(val notificationsAllowed: Boolean, val exactAllowed: Boolean)

internal fun alarmsToSchedule(plan: DeliveryPlan?, now: Instant, access: AlarmAccess): Map<String, Instant> {
    if (plan == null || now > plan.horizon || !access.notificationsAllowed) return emptyMap()
    return plan.notifications.filter { entry ->
        entry.isDeliverableAt(now) && entry.fire <= plan.horizon && (entry.fire >= now || entry.originalFire != null)
    }.associate { it.key to maxOf(it.fire, now) }
}

class AlarmScheduler internal constructor(
    private val app: Context,
    private val inventory: android.content.SharedPreferences,
) {
    private val alarmManager = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val lock = Mutex()

    fun access(): AlarmAccess {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Station reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val granted = Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return AlarmAccess(
            granted && notifications.areNotificationsEnabled() &&
                notifications.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE,
            Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms(),
        )
    }

    suspend fun syncLatest() = withContext(Dispatchers.IO) {
        lock.withLock {
            val plan = DeliveryPlanStore.get(app).plan.first()
            applyPlan(plan, Instant.now(), access())
        }
    }

    internal fun applyPlan(plan: DeliveryPlan?, now: Instant, access: AlarmAccess) {
        val desired = alarmsToSchedule(plan, now, access)
        val previous = inventory.getStringSet("keys", emptySet()).orEmpty()
        // An inexact alarm may still be waiting in Android after its fire time.
        // Keep existing tokens for those entries; a reboot must not recreate the overdue alarm.
        val delayed = if (access.notificationsAllowed) plan?.notifications.orEmpty().filter {
            it.fire < now && it.isDeliverableAt(now) && it.key in previous && it.key !in desired
        }.mapTo(mutableSetOf()) { it.key } else emptySet()
        val keep = desired.keys + delayed
        // Record every possibly live token before touching AlarmManager so a retry can cancel it.
        saveInventory(previous + keep)
        for (key in previous - keep) {
            token(key, PendingIntent.FLAG_NO_CREATE)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
        for ((key, fire) in desired) {
            val token = checkNotNull(token(key, PendingIntent.FLAG_UPDATE_CURRENT))
            val millis = fire.toEpochMilli() + if (fire.nano % 1_000_000 == 0) 0L else 1L
            if (access.exactAllowed) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, token)
                    continue
                } catch (_: SecurityException) {
                    // Authorization may change between the check and scheduling.
                }
            }
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, token)
        }
        saveInventory(keep)
    }

    private fun saveInventory(keys: Set<String>) {
        if (inventory.getStringSet("keys", emptySet()) == keys) return
        if (!inventory.edit().putStringSet("keys", keys).commit()) throw IOException("Could not save reminder alarm inventory")
    }

    private fun token(key: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        app, 0, intent(app, key), flags or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "station_reminders"
        private const val ACTION = "io.openwaters.slackwater.android.REMINDER"
        private var shared: AlarmScheduler? = null

        @Synchronized
        fun get(context: Context): AlarmScheduler {
            val app = context.applicationContext
            return shared ?: AlarmScheduler(app, app.getSharedPreferences("reminder_alarm_inventory", Context.MODE_PRIVATE))
                .also { shared = it }
        }

        internal fun intent(context: Context, key: String) = Intent(context, AlertReceiver::class.java)
            .setAction(ACTION)
            .setData(Uri.Builder().scheme("slackwater-reminder").authority("occurrence").appendPath(key).build())

        fun occurrenceKey(intent: Intent): String? {
            val uri = intent.data ?: return null
            if (intent.action != ACTION || uri.scheme != "slackwater-reminder" || uri.authority != "occurrence") return null
            return uri.pathSegments.singleOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}
