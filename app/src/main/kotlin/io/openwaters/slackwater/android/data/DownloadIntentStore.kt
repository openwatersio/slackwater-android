package io.openwaters.slackwater.android.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.openwaters.slackwater.android.core.station.StationCatalog
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.core.station.StationResolution
import io.openwaters.slackwater.android.core.station.remapSavedIds
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

data class DownloadIntent(
    val requested: Set<StationId> = emptySet(),
    val cancelled: Set<StationId> = emptySet(),
    val previewRequested: Set<StationId> = emptySet(),
) {
    /** Filter existing queue candidates by current catalog ID after checking stored coverage. */
    fun allowsRestore(canonicalId: StationId, ready: Boolean): Boolean =
        ready || canonicalId in requested || canonicalId !in cancelled
}

private val versionKey = intPreferencesKey("version")
private val requestedKey = stringSetPreferencesKey("requested")
private val knownRequestsKey = stringSetPreferencesKey("knownGeneralRequests")
private val previewRequestedKey = stringSetPreferencesKey("previewRequested")
private val cancelledKey = stringSetPreferencesKey("cancelled")

private class InvalidDownloadIntent(message: String) : IOException(message)

private fun Preferences.ids(key: Preferences.Key<Set<String>>): Set<StationId> {
    val value = asMap()[key] ?: return emptySet()
    if (value !is Set<*>) throw InvalidDownloadIntent("Invalid download intent: ${key.name}")
    return value.mapTo(mutableSetOf()) {
        StationId(it as? String ?: throw InvalidDownloadIntent("Invalid station ID in ${key.name}"))
    }
}

private fun Preferences.readIntent(): DownloadIntent {
    val fields = asMap()
    val version = fields[versionKey]
    if (version != null && version != 1) throw InvalidDownloadIntent("Unsupported download intent version: $version")
    return DownloadIntent(ids(requestedKey), ids(cancelledKey), ids(previewRequestedKey))
}

private fun MutablePreferences.storeIntent(intent: DownloadIntent) {
    this[versionKey] = 1
    this[requestedKey] = intent.requested.mapTo(mutableSetOf()) { it.value }
    this[knownRequestsKey] = this[requestedKey].orEmpty()
    this[previewRequestedKey] = intent.previewRequested.mapTo(mutableSetOf()) { it.value }
    this[cancelledKey] = intent.cancelled.mapTo(mutableSetOf()) { it.value }
}

class DownloadIntentStore internal constructor(
    private val data: DataStore<Preferences>,
    private val catalog: StationCatalog,
) {
    val state: Flow<Result<DownloadIntent>> = data.data.map { Result.success(it.readIntent()) }
        .retryWhen { cause, attempt ->
            if (cause !is IOException || cause is InvalidDownloadIntent || cause is androidx.datastore.core.CorruptionException) return@retryWhen false
            emit(Result.failure(cause))
            delay(1_000L shl attempt.coerceAtMost(5).toInt())
            true
        }
        .catch { if (it is IOException) emit(Result.failure(it)) else throw it }
        .distinctUntilChanged()

    /** A direct request or explicit retry also resumes a previously trimmed job. */
    suspend fun requestDownloads(ids: Collection<StationId>) = editDownloads(ids) { intent, stations ->
        intent.copy(requested = intent.requested + stations, cancelled = intent.cancelled - stations)
    }

    /** A successful download or permanent failure releases its request. Transient failures retain it. */
    suspend fun releaseDownloadRequests(ids: Collection<StationId>) = editDownloads(ids) { intent, stations ->
        intent.copy(requested = intent.requested - stations)
    }

    suspend fun requestPreviews(ids: Collection<StationId>) = editDownloads(ids) { intent, stations ->
        intent.copy(previewRequested = intent.previewRequested + stations)
    }

    suspend fun releasePreviews(ids: Collection<StationId>) = editDownloads(ids) { intent, stations ->
        intent.copy(previewRequested = intent.previewRequested - stations)
    }

    /** Narrowing the download tier cancels automatic jobs while preserving explicit requests. */
    suspend fun trimDownloads(ids: Collection<StationId>) = editDownloads(ids) { intent, stations ->
        intent.copy(cancelled = intent.cancelled + (stations - intent.requested))
    }

    suspend fun resumeAutomaticDownloads(ids: Collection<StationId>) = editDownloads(ids) { intent, stations ->
        intent.copy(cancelled = intent.cancelled - stations)
    }

    private suspend fun editDownloads(
        ids: Collection<StationId>,
        change: (DownloadIntent, Set<StationId>) -> DownloadIntent,
    ) {
        if (ids.isEmpty()) return
        val stations = catalog.remapSavedIds(ids.toList()).toSet()
        data.edit { prefs ->
            val previous = prefs.readIntent()
            val next = change(previous, stations)
            if (next != previous) prefs.storeIntent(next)
        }
    }

    companion object {
        internal fun open(
            file: File, catalog: StationCatalog, scope: CoroutineScope,
            gateReferences: () -> Set<StationId> = { emptySet() },
        ): DownloadIntentStore {
            val migration = object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences) = true
                override suspend fun migrate(currentData: Preferences): Preferences {
                    val previous = currentData.readIntent()
                    val requested = catalog.remapSavedIds(previous.requested.toList()).toSet()
                    val known = when {
                        currentData.contains(knownRequestsKey) -> currentData.ids(knownRequestsKey)
                        currentData.contains(previewRequestedKey) -> previous.requested
                        else -> emptySet()
                    }
                    val added = requested - catalog.remapSavedIds(known.toList()).toSet()
                    val previews = catalog.remapSavedIds(previous.previewRequested.toList()).toMutableSet()
                    if (added.isNotEmpty()) {
                        // A gate requested its reference tide under the same ID as a direct tide download.
                        val references = try {
                            gateReferences()
                        } catch (invalid: org.json.JSONException) {
                            throw InvalidDownloadIntent("Invalid bundled gate references: ${invalid.message}")
                        }
                        added.filterTo(previews) { id ->
                            id !in references && (catalog.resolve(id) as? StationResolution.Live)?.identity?.kind == StationKind.CanadianTide
                        }
                    }
                    val next = DownloadIntent(requested, catalog.remapSavedIds(previous.cancelled.toList()).toSet(), previews)
                    if (currentData.contains(knownRequestsKey) && known == requested && next == previous) return currentData
                    return currentData.toMutablePreferences().apply { storeIntent(next) }
                }
                override suspend fun cleanUp() = Unit
            }
            return DownloadIntentStore(PreferenceDataStoreFactory.create(
                migrations = listOf(migration), scope = scope, produceFile = { file },
            ), catalog)
        }
    }
}
