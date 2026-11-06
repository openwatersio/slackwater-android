package io.openwaters.slackwater.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.openwaters.slackwater.android.core.planning.AlertRule
import io.openwaters.slackwater.android.core.planning.allows
import io.openwaters.slackwater.android.core.planning.deliveryPlan
import io.openwaters.slackwater.android.core.planning.AlertOccurrence
import io.openwaters.slackwater.android.core.planning.DeliveryPlan
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val planKey = stringPreferencesKey("deliveryPlan.v1")

// Keep a zero-lead claim through its event minute, including subsequent foreground rebuilds.
private val AlertOccurrence.keepClaimThrough: Instant get() = if (atEventTime) event.plusSeconds(60) else event

private val deliveredKey = stringPreferencesKey("deliveredReminders.v1")

private fun Preferences.delivered(now: Instant): MutableMap<String, Instant> {
    val rows = this[deliveredKey]?.let(::JSONObject) ?: return mutableMapOf()
    return rows.keys().asSequence().associateWith { Instant.parse(rows.getString(it)) }
        .filterValues { it >= now }.toMutableMap()
}

private fun List<AlertOccurrence>.encode() = JSONArray().also { entries ->
    for (entry in this) entries.put(JSONObject().apply {
        put("ruleId", entry.ruleId.toString())
        put("event", entry.event.toString())
        put("fire", entry.fire.toString())
        entry.originalFire?.let { put("originalFire", it.toString()) }
        entry.end?.let { put("end", it.toString()) }
        put("noWindow", entry.noWindow)
        entry.heightM?.let { put("heightM", it) }
    })
}

private fun JSONArray.occurrences() = List(length()) { index ->
    val entry = getJSONObject(index)
    AlertOccurrence(
        ruleId = UUID.fromString(entry.getString("ruleId")),
        event = Instant.parse(entry.getString("event")),
        fire = Instant.parse(entry.getString("fire")),
        originalFire = if (entry.has("originalFire")) Instant.parse(entry.getString("originalFire")) else null,
        end = if (entry.has("end")) Instant.parse(entry.getString("end")) else null,
        noWindow = entry.getBoolean("noWindow"),
        heightM = if (entry.has("heightM")) entry.getDouble("heightM") else null,
    )
}

private fun DeliveryPlan.encode() = JSONObject().apply {
    put("horizon", horizon.toString())
    put("calendar", calendar.encode())
    put("notifications", notifications.encode())
    put("overflow", overflow.encode())
}.toString()

private fun String.decodePlan(): DeliveryPlan {
    val saved = JSONObject(this)
    return DeliveryPlan(Instant.parse(saved.getString("horizon")), saved.getJSONArray("calendar").occurrences(),
        saved.getJSONArray("notifications").occurrences(), saved.getJSONArray("overflow").occurrences())
}

class DeliveryPlanStore internal constructor(private val data: DataStore<Preferences>) {
    val plan: Flow<DeliveryPlan?> = data.data.map { it[planKey]?.decodePlan() }

    suspend fun rebuild(
        rules: List<AlertRule>,
        occurrences: List<AlertOccurrence>,
        calendar: List<AlertOccurrence>,
        now: Instant,
        entitlement: ReminderEntitlement,
        newlySavedRuleIds: Set<UUID> = emptySet(),
    ) {
        data.edit { prefs ->
            val delivered = prefs.delivered(now)
            prefs[planKey] = deliveryPlan(rules, occurrences, calendar, now, entitlement,
                newlySavedRuleIds, prefs[planKey]?.decodePlan(), delivered.keys).encode()
            prefs[deliveredKey] = JSONObject(delivered.mapValues { it.value.toString() }).toString()
        }
    }

    suspend fun refill(
        deliveredKeys: Set<String>, now: Instant, entitlement: ReminderEntitlement, keepDueBroadcasts: Boolean = false,
    ): DeliveryPlan? {
        val updated = data.edit { prefs ->
            val previous = prefs[planKey]?.decodePlan() ?: return@edit
            val delivered = prefs.delivered(now)
            for (entry in previous.notifications) {
                if (entry.key in deliveredKeys && entry.isDeliverableAt(now)) delivered[entry.key] = entry.keepClaimThrough
            }
            prefs[deliveredKey] = JSONObject(delivered.mapValues { it.value.toString() }).toString()
            prefs[planKey] = previous.refill(deliveredKeys, now, entitlement, keepDueBroadcasts).encode()
        }
        return updated[planKey]?.decodePlan()
    }

    /** Claim exactly the plan entry we validated. Duplicate broadcasts and stale reads lose the claim. */
    internal suspend fun consume(
        expected: AlertOccurrence, now: Instant, entitlement: ReminderEntitlement, valid: Boolean,
    ): Boolean {
        var claimed = false
        data.edit { prefs ->
            val previous = prefs[planKey]?.decodePlan() ?: return@edit
            if (expected !in previous.notifications || expected.fire > now) return@edit
            val delivered = prefs.delivered(now)
            claimed = valid && expected.isDeliverableAt(now) && entitlement.allows(now) && expected.key !in delivered
            if (claimed) delivered[expected.key] = expected.keepClaimThrough
            prefs[deliveredKey] = JSONObject(delivered.mapValues { it.value.toString() }).toString()
            prefs[planKey] = previous.refill(setOf(expected.key), now, entitlement, keepDueBroadcasts = true).encode()
        }
        return claimed
    }

    companion object {
        private var shared: DeliveryPlanStore? = null

        @Synchronized
        fun get(context: Context): DeliveryPlanStore = shared ?: DeliveryPlanStore(
            PreferenceDataStoreFactory.create {
                File(context.applicationContext.noBackupFilesDir, "delivery_plan.preferences_pb")
            },
        ).also { shared = it }
    }
}
