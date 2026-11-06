package io.openwaters.slackwater.android.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.openwaters.slackwater.android.core.station.StationCatalog
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.remapSavedIds
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException

data class SavedPlaces(val favorites: List<StationId>, val recents: List<StationId>)

private val favoritesKey = stringPreferencesKey("favorites")
private val recentsKey = stringPreferencesKey("recents")
private val versionKey = intPreferencesKey("savedPlacesVersion")
private const val recentLimit = 6

private fun Preferences.ids(key: Preferences.Key<String>): List<StationId> {
    val stored = asMap()[key] ?: return emptyList()
    val raw = stored as? String ?: throw IOException("Invalid type for ${key.name}")
    try {
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            StationId(array.get(index) as? String ?: throw IOException("Expected a station ID in ${key.name}"))
        }
    } catch (error: JSONException) {
        throw IOException("Invalid saved list: ${key.name}", error)
    }
}

private fun Preferences.readPlaces(): SavedPlaces {
    val version = asMap()[versionKey]
    if (version != null && version != 1) throw IOException("Unsupported saved places version: $version")
    return SavedPlaces(ids(favoritesKey), ids(recentsKey))
}

private fun List<StationId>.encode() = JSONArray(map { it.value }).toString()

private fun List<StationId>.record(id: StationId) = (listOf(id) + (this - id)).take(recentLimit)

class UserStateStore private constructor(
    private val data: DataStore<Preferences>,
    private val catalog: StationCatalog,
) {
    val places: Flow<Result<SavedPlaces>> = data.data
        .map { Result.success(it.readPlaces()) }
        .catch { error ->
            if (error !is IOException) throw error
            emit(Result.failure(error))
        }
        .distinctUntilChanged()

    suspend fun recordVisit(id: StationId) = update(id) { places, station ->
        places.copy(recents = places.recents.record(station))
    }

    suspend fun removeRecent(id: StationId) = update(id) { places, station ->
        places.copy(recents = places.recents - station)
    }

    suspend fun setFavorite(id: StationId, starred: Boolean) = update(id) { places, station ->
        when {
            starred && station !in places.favorites -> places.copy(favorites = places.favorites + station)
            !starred && station in places.favorites -> places.copy(
                favorites = places.favorites - station,
                recents = places.recents.record(station),
            )
            else -> places
        }
    }

    suspend fun forgetFavorite(id: StationId) = update(id) { places, station ->
        places.copy(favorites = places.favorites - station)
    }

    suspend fun replaceFavorite(old: StationId, replacement: StationId) {
        val new = catalog.resolve(replacement).id
        update(old) { places, station ->
            val favorites = places.favorites
            when {
                station !in favorites || station == new -> places
                new in favorites -> places.copy(favorites = favorites - station)
                else -> places.copy(favorites = favorites.map { if (it == station) new else it })
            }
        }
    }

    private suspend fun update(id: StationId, change: (SavedPlaces, StationId) -> SavedPlaces) {
        val station = catalog.resolve(id).id
        data.edit { preferences ->
            val previous = preferences.readPlaces()
            val next = change(previous, station)
            if (next != previous) {
                preferences[versionKey] = 1
                if (next.favorites != previous.favorites) preferences[favoritesKey] = next.favorites.encode()
                if (next.recents != previous.recents) preferences[recentsKey] = next.recents.encode()
            }
        }
    }

    companion object {
        private var shared: UserStateStore? = null

        @Synchronized
        fun get(context: Context): UserStateStore {
            val app = context.applicationContext
            return shared ?: open(
                File(app.noBackupFilesDir, "saved_places.preferences_pb"),
                BundledStationCatalog(app.assets),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { shared = it }
        }

        internal fun open(file: File, catalog: StationCatalog, scope: CoroutineScope): UserStateStore {
            // Run on each open because a catalog refresh can publish new successors.
            val migration = object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences) = true
                override suspend fun migrate(currentData: Preferences): Preferences {
                    val places = currentData.readPlaces()
                    val favorites = catalog.remapSavedIds(places.favorites)
                    val recents = catalog.remapSavedIds(places.recents).take(recentLimit)
                    if (favorites == places.favorites && recents == places.recents) return currentData
                    return currentData.toMutablePreferences().apply {
                        this[versionKey] = 1
                        this[favoritesKey] = favorites.encode()
                        this[recentsKey] = recents.encode()
                    }
                }
                override suspend fun cleanUp() = Unit
            }
            return UserStateStore(PreferenceDataStoreFactory.create(
                migrations = listOf(migration), scope = scope, produceFile = { file },
            ), catalog)
        }
    }
}
