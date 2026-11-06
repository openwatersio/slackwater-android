package io.openwaters.slackwater.android.calendars

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.station.StationId
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

internal data class CalendarState(
    val subscriptions: List<StationSubscription>,
    val invalidations: Map<StationId, PredictionInvalidation>,
)

internal class CalendarStateStore(private val data: DataStore<Preferences>) {
    private val subscriptionsKey = stringPreferencesKey("stationSubscriptions.v1")
    private val invalidationsKey = stringPreferencesKey("predictionInvalidations.v1")
    private fun Preferences.state(): CalendarState {
        val rows = this[subscriptionsKey]?.let(::JSONArray) ?: JSONArray()
        val subscriptions = List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            StationSubscription(
                StationId(row.getString("station")), if (row.has("calendar")) row.getLong("calendar") else null,
                CalendarOrigin.valueOf(row.getString("origin")),
                if (row.has("trialEnd")) Instant.parse(row.getString("trialEnd")) else null,
                row.optBoolean("pendingDeletion"), row.optBoolean("pendingWork"), row.opt("publishedSource") as? String,
            )
        }
        val records = this[invalidationsKey]?.let(::JSONObject) ?: JSONObject()
        val invalidations = records.keys().asSequence().associate { station ->
            val id = StationId(station)
            id to PredictionInvalidation(id, records.getString(station))
        }
        return CalendarState(subscriptions, invalidations)
    }

    val state = data.data.map { it.state() }.distinctUntilChanged()

    suspend fun edit(change: (CalendarState) -> CalendarState) {
        data.edit { prefs ->
            val old = prefs.state()
            val new = change(old)
            if (new.subscriptions != old.subscriptions) {
                prefs[subscriptionsKey] = JSONArray().also { rows ->
                    new.subscriptions.forEach { sub ->
                        rows.put(JSONObject().apply {
                            put("station", sub.stationId.value)
                            put("calendar", sub.calendarId)
                            put("origin", sub.origin.name)
                            put("trialEnd", sub.trialEnd?.toString())
                            put("pendingDeletion", sub.pendingDeletion)
                            put("pendingWork", sub.pendingWork)
                            put("publishedSource", sub.publishedSource)
                        })
                    }
                }.toString()
            }
            if (new.invalidations != old.invalidations) {
                prefs[invalidationsKey] = JSONObject(new.invalidations.values.associate { it.stationId.value to it.revision }).toString()
            }
        }
    }

    /** Called before replacing or removing a model, while the model writer holds alertInputLock. */
    suspend fun invalidate(ids: Set<StationId>) = edit { current ->
        val revision = UUID.randomUUID().toString()
        val affected = current.subscriptions.map { it.stationId }.filter { it in ids }
        current.copy(invalidations = current.invalidations + affected.associateWith { PredictionInvalidation(it, revision) })
    }

    suspend fun convertPurchased() = edit { current ->
        current.copy(subscriptions = current.subscriptions.map { sub ->
            if (sub.origin == CalendarOrigin.FREE_DAY) sub.copy(origin = CalendarOrigin.PURCHASED, trialEnd = null, pendingWork = true)
            else sub
        })
    }

    companion object {
        private var shared: CalendarStateStore? = null
        @Synchronized fun get(context: Context): CalendarStateStore = shared ?: CalendarStateStore(
            PreferenceDataStoreFactory.create { File(context.applicationContext.noBackupFilesDir, "station_calendars.preferences_pb") },
        ).also { shared = it }
    }
}
