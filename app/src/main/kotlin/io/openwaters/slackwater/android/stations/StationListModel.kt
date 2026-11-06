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
import io.openwaters.slackwater.android.data.SavedPlaces
import io.openwaters.slackwater.android.data.UserStateStore
import java.time.Instant
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

/** A favorite that no longer resolves to a live station: shown as a removed card that offers a replacement. */
data class RemovedStation(val id: StationId, val lastKnown: StationIdentity?) {
    /** A Canadian port CHS withdrew, rather than a station Slackwater stopped carrying. */
    val withdrawnByChs: Boolean get() = id.value.startsWith("chs-")
}

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

    // Bumped whenever location inputs change or time passes, so the slot re-reads a fix's age.
    private val slotTick = MutableStateFlow(0L)

    init {
        scope.launch { _catalog.value = LoadedCatalog(catalogSource.identities()) }
    }

    val readingPreferences: StateFlow<ReadingPreferences> = reading.preferences
        .map { it.getOrDefault(ReadingPreferences()) }
        .stateIn(scope, SharingStarted.Eagerly, ReadingPreferences())

    val preferences: StateFlow<ListPreferences> = listPreferences.preferences
        .map { it.getOrDefault(ListPreferences()) }
        .stateIn(scope, SharingStarted.Eagerly, ListPreferences())

    /** Null until the catalog and saved places have loaded. A failed saved-places read shows empty lists. */
    val state: StateFlow<StationListState?> = combine(
        catalog.mapNotNull { it },
        places.places.map { it.getOrDefault(SavedPlaces(emptyList(), emptyList())) },
        preferences,
        combine(location.inputs, slotTick) { _, _ -> location.slot() }.distinctUntilChanged(),
    ) { catalog, saved, list, slot ->
        val sections = stationListSections(
            catalog.stations, slot.fixCoordinate, saved.favorites, saved.recents, saved.chosenIds, list.seriesFilter,
            rank = ::rankOnce,
        )
        val removed = saved.favorites.filter { it !in catalog.byId }.associateWith { id ->
            val lastKnown = when (val resolved = catalogSource.resolve(id)) {
                is StationResolution.Known -> resolved.identity
                else -> null
            }
            RemovedStation(id, lastKnown)
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

    // Ranking the whole catalog is the list's costliest step; a minute's tick or a favorite toggle reuses it.
    private var ranking: Pair<Coordinate, List<RankedStation>>? = null

    private fun rankOnce(stations: List<StationIdentity>, anchor: Coordinate): List<RankedStation> =
        ranking?.takeIf { it.first == anchor }?.second
            ?: rankByDistance(stations, anchor).also { ranking = anchor to it }

    /** Saved-place edits outlive the composition that asked for them. */
    fun launchEdit(edit: suspend () -> Unit) {
        scope.launch { edit() }
    }

    /** Re-reads the slot; the screen calls it on its 60-second tick so an aged-out fix shows. */
    fun tick() {
        slotTick.value++
    }

    suspend fun refreshLocation() {
        location.refresh()
        tick()
    }

    suspend fun reading(id: StationId, at: Instant): CardReading = withContext(Dispatchers.Default) {
        cardReading(predictions.load(id, cardSpan(at), PredictionPurpose.DISPLAY), at)
    }

    suspend fun search(query: String, anchor: Coordinate, series: StationSeries?): SearchResults =
        withContext(Dispatchers.Default) { catalog.value?.search?.search(query, anchor, series) ?: SearchResults(emptyList()) }

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
