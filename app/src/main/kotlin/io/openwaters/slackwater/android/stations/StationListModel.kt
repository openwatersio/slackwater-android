package io.openwaters.slackwater.android.stations

import android.content.Context
import io.openwaters.slackwater.android.core.prediction.PredictionPurpose
import io.openwaters.slackwater.android.core.station.CardReading
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.LocationSlot
import io.openwaters.slackwater.android.core.station.RankedStation
import io.openwaters.slackwater.android.core.station.SearchResults
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationListSections
import io.openwaters.slackwater.android.core.station.StationResolution
import io.openwaters.slackwater.android.core.station.StationSearch
import io.openwaters.slackwater.android.core.station.StationSeries
import io.openwaters.slackwater.android.core.station.cardReading
import io.openwaters.slackwater.android.core.station.cardSpan
import io.openwaters.slackwater.android.core.station.fixCoordinate
import io.openwaters.slackwater.android.core.station.rankByDistance
import io.openwaters.slackwater.android.core.station.stationListSections
import io.openwaters.slackwater.android.data.AndroidPredictionRepository
import io.openwaters.slackwater.android.data.BundledStationCatalog
import io.openwaters.slackwater.android.data.ListPreferences
import io.openwaters.slackwater.android.data.ListPreferencesStore
import io.openwaters.slackwater.android.data.ReadingPreferences
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import io.openwaters.slackwater.android.data.UserStateStore
import android.util.Log
import io.openwaters.slackwater.android.core.station.coordinate
import io.openwaters.slackwater.android.core.station.distanceKm
import io.openwaters.slackwater.android.data.retryAlertReads
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import io.openwaters.slackwater.android.data.SavedPlaces
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The live catalog, loaded once off the main thread. */
class LoadedCatalog(val stations: List<StationIdentity>) {
    val byId: Map<StationId, StationIdentity> = stations.associateBy { it.id }
    val search = StationSearch(stations)
}

/**
 * A favorite that no longer resolves to a live station: shown as a removed card that offers a replacement.
 * [withdrawnByChs] is true only for a port in the CHS tombstone list; the card never guesses a reason otherwise.
 */
data class RemovedStation(val id: StationId, val lastKnown: StationIdentity?, val withdrawnByChs: Boolean)

data class StationListState(
    val sections: StationListSections,
    /** The live catalog by id, for the saved sections' cards. */
    val stations: Map<StationId, StationIdentity>,
    val slot: LocationSlot,
    val removed: Map<StationId, RemovedStation>,
    val favorites: Set<StationId>,
    val filter: StationSeries?,
    /** The last-opened place's name, for "Showing places near …"; null on a first launch. */
    val lastOpenedName: String?,
)

/**
 * Everything the station list draws, combined from the catalog, the saved places, the list's own preferences and
 * location. One per process, like the stores it reads.
 */
/** Emits [fallback] for a failed read, then collects again with backoff until a read succeeds. */
private fun <T> Flow<Result<T>>.orRetrying(fallback: T): Flow<T> = flow {
    var attempt = 0
    while (true) {
        var failed = false
        collect { result ->
            result.fold({ attempt = 0; emit(it) }, { failed = true; emit(fallback) })
        }
        if (!failed) return@flow
        delay(1000L shl attempt.coerceAtMost(6))
        attempt++
    }
}

class StationListModel private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val catalogSource = BundledStationCatalog(app.assets)
    private val predictions = AndroidPredictionRepository(app)
    val places = UserStateStore.get(app)
    val listPreferences = ListPreferencesStore.get(app)
    val location = LocationRepository.get(app)
    private val reading = ReadingPreferencesStore.get(app)

    private val _catalog = MutableStateFlow<LoadedCatalog?>(null)
    val catalog: StateFlow<LoadedCatalog?> = _catalog.asStateFlow()

    private val _catalogFailed = MutableStateFlow(false)

    /** The bundled catalog could not be read, so there is nothing to list. */
    val catalogFailed: StateFlow<Boolean> = _catalogFailed.asStateFlow()

    // Bumped whenever location inputs change or time passes, so the slot re-reads a fix's age.
    private val slotTick = MutableStateFlow(0L)

    init {
        scope.launch {
            try {
                _catalog.value = LoadedCatalog(catalogSource.identities())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("Slackwater", "Station catalog unavailable", error)
                _catalogFailed.value = true
            }
        }
    }

    // The stores end their flow after a failed read; collecting again recovers from a transient failure.
    val readingPreferences: StateFlow<ReadingPreferences> = reading.preferences
        .orRetrying(ReadingPreferences())
        .stateIn(scope, SharingStarted.Eagerly, ReadingPreferences())

    /** Null until the stored value has been read, so the ask-once flag is never mistaken for "not asked". */
    val preferences: StateFlow<ListPreferences?> = listPreferences.preferences
        .map { it.getOrThrow() }.retryAlertReads()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Null until the catalog, saved places and list preferences have loaded. */
    val state: StateFlow<StationListState?> = combine(
        catalog.mapNotNull { it },
        // Unreadable or newer-format saved places show as empty lists and stay on disk untouched.
        places.places.orRetrying(SavedPlaces(emptyList(), emptyList())),
        preferences.filterNotNull(),
        combine(location.inputs, slotTick) { _, _ -> location.slot() }.distinctUntilChanged(),
    ) { catalog, saved, list, slot ->
        val sections = stationListSections(
            catalog.stations, slot.fixCoordinate, saved.favorites, saved.recents, saved.chosenIds, list.seriesFilter,
            rank = ::rankOnce,
        )
        val removed = saved.favorites.filter { it !in catalog.byId }.associateWith { id ->
            val resolved = catalogSource.resolve(id)
            RemovedStation(
                id = id,
                lastKnown = (resolved as? StationResolution.Known)?.identity,
                withdrawnByChs = resolved is StationResolution.Tombstone && id.value.startsWith("chs-"),
            )
        }
        StationListState(
            sections = sections,
            stations = catalog.byId,
            slot = slot,
            removed = removed,
            favorites = saved.favorites.toSet(),
            filter = list.seriesFilter,
            lastOpenedName = saved.recents.firstNotNullOfOrNull(catalog.byId::get)?.name,
        )
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    // Sorting the whole catalog is the list's costliest step. Like iOS, the order is reused while the anchor stays
    // in one 0.001° cell, so GPS jitter, a tick or a favorite toggle does not re-sort; every distance, including the
    // hero radius check, is still measured from the exact anchor. Only the state's one combine coroutine touches it.
    private var ranking: Pair<Pair<Long, Long>, List<StationIdentity>>? = null

    private fun rankOnce(stations: List<StationIdentity>, anchor: Coordinate): List<RankedStation> {
        val cell = Math.round(anchor.latitude * 1000) to Math.round(anchor.longitude * 1000)
        val order = ranking?.takeIf { it.first == cell }?.second
            ?: rankByDistance(stations, anchor).map { it.identity }.also { ranking = cell to it }
        return order.map { RankedStation(it, distanceKm(anchor, it.coordinate)) }
    }

    /**
     * Saved-place edits outlive the composition that asked for them. A store that cannot be read or written keeps
     * its bytes and the edit is dropped, rather than ending the app.
     */
    fun launchEdit(edit: suspend () -> Unit) {
        scope.launch {
            try {
                edit()
            } catch (error: IOException) {
                Log.w("Slackwater", "Saved places unavailable; edit not saved", error)
            }
        }
    }

    /** Re-reads the slot; the screen calls it on its 60-second tick so an aged-out fix shows. */
    fun tick() {
        slotTick.value++
    }

    suspend fun refreshLocation() {
        location.refresh()
        tick()
    }

    /** One card's reading. A station that fails to load reads as unavailable; the card refreshes on the next tick. */
    suspend fun reading(id: StationId, at: Instant): CardReading = withContext(Dispatchers.Default) {
        try {
            cardReading(predictions.load(id, cardSpan(at), PredictionPurpose.DISPLAY), at)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("Slackwater", "Reading unavailable for ${id.value}", error)
            CardReading.Unavailable
        }
    }

    /** Ranked results from the loaded catalog; the caller waits for [catalog] before asking. */
    suspend fun search(catalog: LoadedCatalog, query: String, anchor: Coordinate, series: StationSeries?): SearchResults =
        withContext(Dispatchers.Default) { catalog.search.search(query, anchor, series) }

    /** The five live stations nearest a removed one's last position, or the list's anchor without one. */
    suspend fun replacements(removed: RemovedStation, fallback: Coordinate): List<RankedStation> = withContext(Dispatchers.Default) {
        val origin = removed.lastKnown?.let { Coordinate(it.latitude, it.longitude) } ?: fallback
        rankByDistance(catalog.value?.stations.orEmpty(), origin).take(5)
    }

    companion object {
        private var shared: StationListModel? = null

        @Synchronized
        fun get(context: Context): StationListModel = shared ?: StationListModel(context).also { shared = it }
    }
}
