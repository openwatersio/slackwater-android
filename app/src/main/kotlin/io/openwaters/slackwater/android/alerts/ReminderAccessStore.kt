package io.openwaters.slackwater.android.alerts

import io.openwaters.slackwater.android.data.alertInputLock
import kotlinx.coroutines.sync.withLock
import android.content.Context
import androidx.datastore.preferences.core.*
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.core.planning.allows
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.*

/** Billing publishes entitlement here so an alarm can check it in a newly started process. */
class ReminderAccessStore private constructor(context: Context) {
    private val data = PreferenceDataStoreFactory.create {
        File(context.noBackupFilesDir, "reminder_access.preferences_pb")
    }
    private val purchased = booleanPreferencesKey("purchased")
    private val expiry = stringPreferencesKey("freeDayEnd")
    val changes: Flow<ReminderEntitlement> = data.data.map { prefs ->
        when {
            prefs[purchased] == true -> ReminderEntitlement.Purchased
            prefs[expiry] != null -> ReminderEntitlement.FreeDay(Instant.parse(prefs[expiry]))
            else -> ReminderEntitlement.Free
        }
    }.distinctUntilChanged()

    suspend fun current() = changes.first()

    suspend fun update(access: ReminderEntitlement): Unit = alertInputLock.withLock {
        data.edit { prefs ->
            prefs.clear()
            when (access) {
                ReminderEntitlement.Purchased -> prefs[purchased] = true
                is ReminderEntitlement.FreeDay -> prefs[expiry] = access.ends.toString()
                ReminderEntitlement.Free -> Unit
            }
        }
    }

    companion object {
        private var shared: ReminderAccessStore? = null
        @Synchronized fun get(context: Context) = shared ?: ReminderAccessStore(context.applicationContext).also { shared = it }
    }
}

fun ReminderEntitlement.activeAt(now: Instant): Boolean = allows(now)
