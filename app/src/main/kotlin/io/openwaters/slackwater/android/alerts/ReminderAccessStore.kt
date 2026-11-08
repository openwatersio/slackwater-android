package io.openwaters.slackwater.android.alerts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.openwaters.slackwater.android.calendars.CalendarStateStore
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.core.planning.allows
import io.openwaters.slackwater.android.core.premium.FreeDayRecord
import io.openwaters.slackwater.android.core.premium.FreeDayStatus
import io.openwaters.slackwater.android.core.premium.status
import io.openwaters.slackwater.android.data.alertInputLock
import io.openwaters.slackwater.android.premium.DeviceFreeDayCalendar
import java.time.DateTimeException
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

/** Billing publishes entitlement here so an alarm can check it in a newly started process. */
class ReminderAccessStore internal constructor(
    private val data: DataStore<Preferences>,
    private val convertCalendars: suspend () -> Unit,
) {
    private val purchased = booleanPreferencesKey("purchased")
    private val expiry = stringPreferencesKey("freeDayEnd")
    private val start = stringPreferencesKey("freeDayStart")
    private val pendingPurchase = booleanPreferencesKey("pendingCalendarPurchase")
    private val day = Duration.ofDays(1)

    private fun Preferences.ownsPurchase() = asMap()[purchased] == true

    private fun Preferences.trialStart(): Instant? {
        val raw = asMap()
        fun parse(value: Any?, legacy: Boolean): Instant? {
            if (value !is String) return null
            return try {
                val instant = Instant.parse(value)
                val started = if (legacy) instant.minus(day) else instant
                started.toEpochMilli()
                started.plus(day).toEpochMilli()
                started
            } catch (_: DateTimeException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
        }
        return parse(raw[start], false) ?: parse(raw[expiry], true)
    }

    private fun Preferences.hasTrialHistory() = start in asMap() || expiry in asMap()

    private fun Preferences.record(): FreeDayRecord {
        val started = trialStart()
        return FreeDayRecord(ownsPurchase(), started, started != null || !hasTrialHistory())
    }

    val freeDayRecord: Flow<FreeDayRecord> = data.data.map { it.record() }.distinctUntilChanged()

    val changes: Flow<ReminderEntitlement> = freeDayRecord.map { record ->
        val started = record.started
        when {
            record.purchased -> ReminderEntitlement.Purchased
            started != null -> ReminderEntitlement.FreeDay(started.plus(day), started)
            else -> ReminderEntitlement.Free
        }
    }.distinctUntilChanged()

    suspend fun current() = changes.first()

    suspend fun startFreeDay(now: Instant, zone: ZoneId): Boolean = alertInputLock.withLock {
        var accepted = false
        data.edit { prefs ->
            val record = prefs.record()
            if (record.status(now, zone, DeviceFreeDayCalendar()) == FreeDayStatus.Available) {
                prefs[start] = now.toString()
                prefs[expiry] = now.plus(day).toString()
                accepted = true
            }
        }
        accepted
    }

    suspend fun syncPurchase(hasPurchase: Boolean): Unit = alertInputLock.withLock {
        writePurchase(hasPurchase)
    }

    private suspend fun writePurchase(hasPurchase: Boolean) {
        data.edit { prefs ->
            if (hasPurchase) {
                if (!prefs.ownsPurchase()) prefs[pendingPurchase] = true
                prefs[purchased] = true
            } else {
                prefs.remove(purchased)
            }
        }
        finishPendingCalendarPurchase()
    }

    /** Caller holds alertInputLock so a new trial subscription cannot join an older promotion. */
    internal suspend fun finishPendingCalendarPurchase() {
        data.edit { prefs ->
            if (prefs.asMap()[pendingPurchase] == true) {
                convertCalendars()
                prefs.remove(pendingPurchase)
            }
        }
    }

    /** Seeds an explicit access state for instrumentation fixtures. Purchase reconciliation uses syncPurchase. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun update(access: ReminderEntitlement): Unit = alertInputLock.withLock {
        if (access == ReminderEntitlement.Purchased) {
            writePurchase(true)
        } else {
            data.edit { prefs ->
                prefs.clear()
                if (access is ReminderEntitlement.FreeDay) prefs[expiry] = access.ends.toString()
            }
        }
    }

    companion object {
        private var shared: ReminderAccessStore? = null
        @Synchronized fun get(context: Context) = shared ?: ReminderAccessStore(
            PreferenceDataStoreFactory.create {
                File(context.applicationContext.noBackupFilesDir, "reminder_access.preferences_pb")
            },
            CalendarStateStore.get(context)::convertPurchased,
        ).also { shared = it }
    }
}

fun ReminderEntitlement.activeAt(now: Instant): Boolean = allows(now)
