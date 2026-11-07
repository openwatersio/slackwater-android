package io.openwaters.slackwater.android.stations

import android.content.Context
import io.openwaters.slackwater.android.core.prediction.PredictionPurpose
import io.openwaters.slackwater.android.core.prediction.PredictionResult
import io.openwaters.slackwater.android.core.station.CardCurve
import io.openwaters.slackwater.android.core.station.CardReading
import io.openwaters.slackwater.android.core.station.approximate
import io.openwaters.slackwater.android.core.station.blockAt
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
import io.openwaters.slackwater.android.core.station.series
import io.openwaters.slackwater.android.data.retryAlertReads
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import io.openwaters.slackwater.android.data.SavedPlaces
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import io.openwaters.slackwater.android.core.station.FIX_MAXIMUM_AGE_MILLIS
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

/** What a card shows at one moment: the reading on its right and the curve behind it. */
data class CardData(val reading: CardReading, val curve: CardCurve?, val approximate: Boolean)

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
            var attempt = 0
            while (_catalog.value == null) {
                try {
                    _catalog.value = LoadedCatalog(catalogSource.identities())
                    _catalogFailed.value = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (attempt == 0) Log.e("Slackwater", "Station catalog unavailable; will retry", error)
                    _catalogFailed.value = true
                    delay(1000L shl attempt.coerceAtMost(6))
                    attempt++
                }
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

    /** Null until the catalog and saved places have loaded. */
    val state: StateFlow<StationListState?> = combine(
        catalog.mapNotNull { it },
        // Unreadable or newer-format saved places show as empty lists and stay on disk untouched.
        places.places.orRetrying(SavedPlaces(emptyList(), emptyList())),
        // The filter falls back to All while its file is unreadable; only the ask waits for a real read.
        listPreferences.preferences.orRetrying(ListPreferences()),
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

    // Sorting the whole catalog from scratch is the list's costliest step. Within one 0.001° cell of the last
    // anchor the previous order is nearly right, so it is re-sorted by the exact distances (a stable sort of an
    // almost-sorted list is close to linear) rather than ranked again. Ties keep the previous order, which came from
    // catalog order. Only the state's one combine coroutine touches it.
    private var ranking: Pair<Pair<Long, Long>, List<StationIdentity>>? = null

    private fun rankOnce(stations: List<StationIdentity>, anchor: Coordinate): List<RankedStation> {
        val cell = Math.round(anchor.latitude * 1000) to Math.round(anchor.longitude * 1000)
        val previous = ranking?.takeIf { it.first == cell }?.second
        val ranked = previous?.map { RankedStation(it, distanceKm(anchor, it.coordinate)) }?.sortedBy { it.distanceKm }
            ?: rankByDistance(stations, anchor)
        ranking = cell to ranked.map { it.identity }
        return ranked
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

    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    // Held in memory as well as stored, so a write that fails can never make the list ask a second time.
    private val askedThisProcess = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Whether the list should raise the location prompt now: only on a first launch, once. A person who allowed
     * location before the list ever asked is recorded as asked, so revoking it later never brings the prompt back.
     */
    fun claimLocationAsk(storedAsked: Boolean): Boolean {
        if (storedAsked || askedThisProcess.get()) return false
        if (location.inputs.value.permission != io.openwaters.slackwater.android.core.station.LocationPermission.NOT_GRANTED) {
            askedThisProcess.set(true)
            launchEdit { listPreferences.markLocationAsked() }
            return false
        }
        return askedThisProcess.compareAndSet(false, true)
    }

    /** Asks for a fix unless a request is already running, so ticks never pile up requests. */
    suspend fun refreshLocation() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            location.refresh()
        } finally {
            refreshing.set(false)
            tick()
        }
    }

    /**
     * Whether the list's 60-second tick should ask for a new fix: while still locating, or once the held fix is
     * halfway to the reuse limit, so it is replaced before it expires and the list never falls back mid-use.
     * A failed request is retried when the list next appears, not every minute.
     */
    fun wantsFreshFix(nowElapsedRealtimeMillis: Long): Boolean = when (val slot = location.slot()) {
        LocationSlot.Locating -> true
        is LocationSlot.Located -> nowElapsedRealtimeMillis - slot.fix.elapsedRealtimeMillis >= FIX_MAXIMUM_AGE_MILLIS / 2
        else -> false
    }

    /**
     * One card at a moment: its reading and its curve, from one prediction load. The curve's slack windows use
     * [thresholdKnots], the station's effective comfort current as the card last saw it. A station that fails to
     * load reads as unavailable with no curve; the card refreshes on the next tick.
     */
    suspend fun card(id: StationId, at: Instant, thresholdKnots: Double): CardData = withContext(Dispatchers.Default) {
        try {
            val result = predictions.load(id, cardSpan(at), PredictionPurpose.DISPLAY)
            // The curve is built on its own, so a failure drawing it never takes the card's reading with it.
            val curve = try {
                CardCurve.from(result, at, thresholdKnots)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("Slackwater", "Curve unavailable for ${id.value}", error)
                null
            }
            CardData(cardReading(result, at), curve, result.blockAt(at)?.approximate == true)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("Slackwater", "Reading unavailable for ${id.value}", error)
            CardData(CardReading.Unavailable, null, false)
        }
    }

    /** The station's data credit, from the record its predictions come from; null when it has none to read. */
    suspend fun attribution(id: StationId, at: Instant): String? = withContext(Dispatchers.Default) {
        try {
            when (val result = predictions.load(id, cardSpan(at), PredictionPurpose.DISPLAY)) {
                is PredictionResult.Ready -> result.source
                is PredictionResult.Disjoint -> result.blocks.firstOrNull()?.source
                else -> null
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    /** Ranked results from the loaded catalog; the caller waits for [catalog] before asking. */
    suspend fun search(catalog: LoadedCatalog, query: String, anchor: Coordinate, series: StationSeries?): SearchResults =
        withContext(Dispatchers.Default) { catalog.search.search(query, anchor, series) }

    /** The five live stations nearest a removed one's last position, or the list's anchor without one. */
    suspend fun replacements(removed: RemovedStation, fallback: Coordinate): List<RankedStation> = withContext(Dispatchers.Default) {
        val origin = removed.lastKnown?.let { Coordinate(it.latitude, it.longitude) } ?: fallback
        // Only stations the list can show, so a replacement is never one Near Me would hide.
        rankByDistance(catalog.value?.stations.orEmpty().filter { it.series != null }, origin).take(5)
    }

    companion object {
        private var shared: StationListModel? = null

        @Synchronized
        fun get(context: Context): StationListModel = shared ?: StationListModel(context).also { shared = it }
    }
}
