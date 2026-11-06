package io.openwaters.slackwater.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.openwaters.slackwater.android.core.station.StationSeries
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class ListPreferences(
    /** Null shows all series. One choice governs Near Me, search and every detail's Nearby section. */
    val seriesFilter: StationSeries? = null,
    /** The list has raised the system location prompt, which it does once on first appearance and never again. */
    val locationAsked: Boolean = false,
)

private val seriesFilterKey = stringPreferencesKey("seriesFilter")
private val locationAskedKey = booleanPreferencesKey("locationAsked")

private fun Preferences.readList() = ListPreferences(
    seriesFilter = when (asMap()[seriesFilterKey]) {
        "tide" -> StationSeries.Tide
        "current" -> StationSeries.Current
        else -> null
    },
    locationAsked = asMap()[locationAskedKey] as? Boolean ?: false,
)

/** The list's own settings, device-local in the no-backup directory. */
class ListPreferencesStore private constructor(private val data: DataStore<Preferences>) {
    /** A failed read emits a failure and ends the flow; collect again to retry. */
    val preferences: Flow<Result<ListPreferences>> = data.data
        .map { Result.success(it.readList()) }
        .catch { error ->
            if (error !is IOException) throw error
            emit(Result.failure(error))
        }
        .distinctUntilChanged()

    suspend fun setSeriesFilter(series: StationSeries?) = data.edit {
        when (series) {
            null -> it.remove(seriesFilterKey)
            StationSeries.Tide -> it[seriesFilterKey] = "tide"
            StationSeries.Current -> it[seriesFilterKey] = "current"
        }
    }

    suspend fun markLocationAsked() = data.edit { it[locationAskedKey] = true }

    companion object {
        private var shared: ListPreferencesStore? = null

        @Synchronized
        fun get(context: Context): ListPreferencesStore {
            val app = context.applicationContext
            return shared ?: open(
                File(app.noBackupFilesDir, "list_preferences.preferences_pb"),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { shared = it }
        }

        internal fun open(file: File, scope: CoroutineScope) = ListPreferencesStore(
            PreferenceDataStoreFactory.create(
                // A lost filter or ask flag costs one tap or one prompt; replacing keeps the list writable.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}
