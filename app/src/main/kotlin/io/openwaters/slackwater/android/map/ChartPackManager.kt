package io.openwaters.slackwater.android.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.openwaters.slackwater.android.core.map.ChartPackSpec
import java.util.PriorityQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

internal enum class ChartPackProblem { Quota, Storage, Download }

internal data class ChartPackSummary(
    val total: Int = 0,
    val ready: Int = 0,
    val failed: Int = 0,
    val bytes: Long = 0,
    val paused: Boolean = false,
    val dataSaver: Boolean = false,
    val inputsUnavailable: Boolean = false,
    val removed: Boolean = false,
    val removing: Boolean = false,
    val problem: ChartPackProblem? = null,
    val canRetry: Boolean = false,
) {
    val downloading get() = !paused && !dataSaver && !removed && !removing && ready < total
}

internal data class StoredChartArea(val id: Long, val key: String, val spec: ChartPackSpec?, val deleting: Boolean)
internal data class ChartPackChanges(val delete: Set<Long>, val create: List<ChartPackSpec>)

private fun ChartPackSpec?.matches(wanted: ChartPackSpec?): Boolean {
    if (this == null || wanted == null || key != wanted.key || minZoom != wanted.minZoom || maxZoom != wanted.maxZoom) return false
    // Permit sub-centimetre differences from the native region JSON round trip.
    fun near(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-8
    return near(west, wanted.west) && near(east, wanted.east) && near(north, wanted.north) && near(south, wanted.south)
}

private const val REGION_WORK_LIMIT = 4
private const val STALL_CHECK_MS = 60_000L

internal fun chartWorkKeys(
    eligible: List<ChartPackSpec>, creating: Set<String>, underway: Set<String>, lastStall: Map<String, Long> = emptyMap(),
): Set<String> {
    val order = compareBy<ChartPackSpec> { lastStall[it.key] ?: 0L }.thenBy { it.key !in underway }.thenBy { it.minZoom }.thenBy { it.key }
    val free = REGION_WORK_LIMIT - creating.size
    if (free <= 0) return emptySet()
    val best = PriorityQueue(free, order.reversed())
    for (spec in eligible) {
        if (spec.key in creating) continue
        best.add(spec)
        if (best.size > free) best.remove()
    }
    return best.mapTo(mutableSetOf()) { it.key }
}

internal fun sameChartDensity(saved: Float, current: Float) = kotlin.math.abs(saved - current) < 0.0001f

internal fun chartPackChanges(
    wanted: Map<String, ChartPackSpec>, stored: List<StoredChartArea>, creating: Set<String>,
): ChartPackChanges {
    val retained = mutableSetOf<String>()
    val retiring = mutableSetOf<Long>()
    for (area in stored) {
        if (area.deleting) continue
        if (!area.spec.matches(wanted[area.key]) || !retained.add(area.key)) retiring += area.id
    }
    // Finish deleting the old definition before starting its replacement.
    val occupied = stored.mapTo(mutableSetOf()) { it.key }
    return ChartPackChanges(retiring, wanted.values.filter { it.key !in occupied && it.key !in creating })
}

internal fun wantedChartAreas(
    desired: Map<String, ChartPackSpec>, stored: List<StoredChartArea>, keepExistingAreas: Boolean, removed: Boolean, keepExistingStations: Boolean = false,
): Map<String, ChartPackSpec> {
    if (removed) return emptyMap()
    if (!keepExistingAreas && !keepExistingStations) return desired
    val wanted = desired.toMutableMap()
    for (area in stored) {
        // Area metadata identifies the cell, not whether a station or a fix
        // requested it. Preserve both origins until station inputs recover.
        val keep = (area.key.startsWith("area/") && (keepExistingAreas || keepExistingStations)) ||
            (area.key.startsWith("station/") && keepExistingStations)
        if (!area.deleting && keep && area.spec != null) wanted.putIfAbsent(area.key, area.spec)
    }
    return wanted
}

internal class ChartPackDeletion {
    var requested = false
        private set
    var inFlight = false
        private set
    var error: ChartPackProblem? = null
        private set

    fun begin(): Boolean {
        if (inFlight || error != null) return false
        requested = true
        inFlight = true
        return true
    }

    fun retain() {
        if (!inFlight) {
            requested = false
            error = null
        }
    }

    fun failed(problem: ChartPackProblem) {
        error = problem
        inFlight = false
    }

    fun retry(manual: Boolean) {
        if (manual || error != ChartPackProblem.Quota) error = null
    }
}

internal fun removalComplete(loaded: Boolean, stored: Int, creating: Int) = loaded && stored == 0 && creating == 0

// The SDK delivers offline callbacks on the main looper. Keep a single native
// handle per region so activating and deactivating it balances file-source use.
internal class ChartPackManager private constructor(context: Context) {
    companion object {
        private var instance: ChartPackManager? = null
        @Synchronized fun get(context: Context): ChartPackManager =
            instance ?: ChartPackManager(context.applicationContext).also { instance = it }
    }

    private class Pack(val region: OfflineRegion, val key: String, val spec: ChartPackSpec?) {
        var status: OfflineRegionStatus? = null
        var statusRead = false
        var active = false
        var lastProgress = 0L
        var lastStall = 0L
        val deletion = ChartPackDeletion()
        var problem: ChartPackProblem? = null
        val ready get() = status?.let { it.requiredResourceCount > 0 && it.isRequiredResourceCountPrecise && it.isComplete } == true
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("offline-chart-packs", Context.MODE_PRIVATE)
    private val pixelRatio = context.resources.displayMetrics.density
    private val storage = MapLibre.getInstance(context).let { OfflineManager.getInstance(context) }
    private val packs = mutableMapOf<Long, Pack>()
    private val creating = mutableSetOf<String>()
    private val creationErrors = mutableMapOf<String, ChartPackProblem>()
    private var storageError: ChartPackProblem? = null
    private var loaded = false
    private var loading = false
    private var desired: Map<String, ChartPackSpec>? = null
    private var keepExistingAreas = false
    private var keepExistingStations = false
    private var dataSaver = false
    private var budgetInputs: Map<String, ChartPackSpec>? = null
    private var budgetCurrent = false
    private var deferred = emptySet<String>()
    private var wanted = emptyMap<String, ChartPackSpec>()
    private var paused = prefs.getBoolean("paused", false)
    private var removed = prefs.getBoolean("removed", false)
    private val state = MutableStateFlow(ChartPackSummary(paused = paused, removing = removed))
    val summary: StateFlow<ChartPackSummary> = state
    private var reconcilePending = false
    private var checkingStalls = false
    private var publishPending = false
    private var retryPending = false
    private val publish = Runnable {
        publishPending = false
        publishSummary()
    }
    private val retryOperations = Runnable {
        retryPending = false
        creationErrors.entries.removeAll { it.value != ChartPackProblem.Quota }
        for (pack in packs.values) {
            pack.deletion.retry(manual = false)
            if (pack.deletion.requested || pack.problem == ChartPackProblem.Quota) continue
            if (pack.problem != null) {
                pack.problem = null
                requestStatus(pack)
            }
        }
        reconcile()
    }

    init {
        storage.setOfflineMapboxTileCountLimit(CHART_TILE_LIMIT.toLong())
        watchChartDataSaver(context, ::setDataSaverRestricted)
        if (removed) reconcile()
    }

    fun setDesired(specs: Set<ChartPackSpec>, keepExistingAreas: Boolean = false, keepExistingStations: Boolean = false) {
        this.keepExistingStations = keepExistingStations
        this.keepExistingAreas = keepExistingAreas
        desired = specs.associateBy { it.key }
        reconcile()
    }

    fun setDataSaverRestricted(value: Boolean) {
        if (dataSaver == value) return
        dataSaver = value
        reconcile()
    }

    fun pause() {
        paused = true
        prefs.edit().putBoolean("paused", true).apply()
        reconcile()
    }

    fun resume() {
        paused = false
        removed = false
        prefs.edit().putBoolean("paused", false).putBoolean("removed", false).apply()
        reconcile()
    }

    fun remove() {
        removed = true
        paused = false
        prefs.edit().putBoolean("paused", false).putBoolean("removed", true).apply()
        reconcile()
    }

    fun retry() {
        creationErrors.clear()
        storageError = null
        for (pack in packs.values) {
            pack.deletion.retry(manual = true)
            if (pack.deletion.requested || pack.problem == null) continue
            setActive(pack, false)
            pack.problem = null
            if (!removed) requestStatus(pack)
        }
        reconcile()
    }

    private fun scheduleReconcile() {
        if (reconcilePending) return
        reconcilePending = true
        main.postDelayed({
            reconcilePending = false
            reconcile()
        }, 32)
    }

    private fun reconcile() {
        scheduleSummary()
        if (desired == null && !removed) return
        if (!loaded) {
            if (!loading) load()
            return
        }
        // A failed delete can be abandoned when a sailor re-adds the same area.
        if (!removed) for (pack in packs.values) {
            val requestedAgain = !pack.spec.matches(wanted[pack.key]) && pack.spec.matches(desired?.get(pack.key))
            if (pack.deletion.requested && requestedAgain) pack.deletion.retain()
        }
        val stored = packs.values.map { StoredChartArea(it.region.id, it.key, it.spec, it.deletion.requested) }
        wanted = wantedChartAreas(desired.orEmpty(), stored, keepExistingAreas, removed, keepExistingStations)
        // Existing regions report completion asynchronously after catalog listing.
        // Reserve their tiles before deciding which new downloads will fit.
        val statusesKnown = packs.values.none { !it.statusRead && !it.deletion.requested }
        if (statusesKnown && budgetInputs != wanted) {
            val complete = packs.values.filter { it.ready && it.spec.matches(wanted[it.key]) }.mapTo(mutableSetOf()) { it.key }
            val underway = packs.values.filter { it.active || (it.status?.completedResourceCount ?: 0) > 0 }
                .mapTo(mutableSetOf()) { it.key }
            deferred = deferredChartKeys(wanted.values, complete, underway = underway)
            budgetInputs = wanted
        }
        budgetCurrent = budgetInputs == wanted
        creationErrors.keys.retainAll(wanted.keys)
        val changes = chartPackChanges(wanted, stored, creating)
        val candidates = mutableListOf<ChartPackSpec>()
        for (pack in packs.values) {
            val overBudget = budgetCurrent && pack.key in deferred && pack.status != null && !pack.ready
            if (pack.region.id in changes.delete || pack.deletion.requested || overBudget) {
                if (pack.deletion.begin()) delete(pack)
            } else if (pack.status != null && !pack.ready && pack.problem == null && pack.key !in deferred) {
                wanted[pack.key]?.let { candidates += it }
            }
        }
        val allowWork = !paused && !dataSaver && !removed && budgetCurrent
        val mayCreate = statusesKnown && packs.values.none { it.deletion.inFlight }
        val missing = changes.create.filter { it.key !in creationErrors && it.key !in deferred }
        if (mayCreate) candidates.addAll(missing)
        val underway = packs.values.filter { it.active || (it.status?.completedResourceCount ?: 0) > 0 }
            .mapTo(mutableSetOf()) { it.key }
        val queued = allowWork && candidates.size + creating.size > REGION_WORK_LIMIT
        val now = SystemClock.elapsedRealtime()
        if (queued) {
            for (pack in packs.values) if (pack.active && now - pack.lastProgress >= STALL_CHECK_MS) {
                pack.lastStall = now
                pack.lastProgress = now
            }
            if (!checkingStalls) {
                checkingStalls = true
                main.postDelayed({
                    checkingStalls = false
                    reconcile()
                }, STALL_CHECK_MS)
            }
        }
        val stalled = packs.values.filter { it.lastStall > 0 }.associate { it.key to it.lastStall }
        val slots = if (allowWork) chartWorkKeys(candidates, creating, underway, stalled) else emptySet()
        // Release old slots before assigning new ones so even briefly the
        // FileSource never has more active regions than the configured limit.
        for (pack in packs.values) if (pack.active && (pack.key !in slots || pack.deletion.requested)) setActive(pack, false)
        for (pack in packs.values) if (!pack.deletion.requested && pack.key in slots) setActive(pack, true)
        if (mayCreate) missing.filter { it.key in slots }.forEach(::create)
    }

    private fun load() {
        loading = true
        storage.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                loading = false
                loaded = true
                storageError = null
                for (region in offlineRegions.orEmpty()) {
                    val metadata = runCatching { JSONObject(region.metadata.decodeToString()) }.getOrNull() ?: continue
                    val key = metadata.opt("chart") as? String ?: continue
                    attach(region, key, metadata.optString("style"))
                }
                scheduleReconcile()
            }

            override fun onError(error: String) {
                loading = false
                storageError = classify(error, ChartPackProblem.Storage)
                scheduleRetry(storageError)
            }
        })
    }

    private fun create(spec: ChartPackSpec) {
        creating.add(spec.key)
        val bounds = LatLngBounds.from(spec.north, spec.east, spec.south, spec.west)
        val definition = OfflineTilePyramidRegionDefinition(
            BASEMAP_STYLE_URL, bounds, spec.minZoom.toDouble(), spec.maxZoom.toDouble(), pixelRatio,
        )
        val metadata = JSONObject().put("chart", spec.key).put("style", BASEMAP_STYLE_URL)
        storage.createOfflineRegion(definition, metadata.toString().encodeToByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                creating.remove(spec.key)
                attach(offlineRegion, spec.key, BASEMAP_STYLE_URL)
                scheduleReconcile()
            }

            override fun onError(error: String) {
                creating.remove(spec.key)
                if (!removed && desired?.get(spec.key) == spec) {
                    creationErrors[spec.key] = classify(error)
                    scheduleRetry(creationErrors[spec.key])
                }
                scheduleReconcile()
            }
        })
    }

    private fun attach(region: OfflineRegion, key: String, style: String) {
        val definition = region.definition as? OfflineTilePyramidRegionDefinition
        val spec = definition?.takeIf { style == BASEMAP_STYLE_URL && it.styleURL == style && sameChartDensity(it.pixelRatio, pixelRatio) }?.let {
            val bounds = it.bounds ?: return@let null
            ChartPackSpec(key, bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast,
                it.minZoom.toInt(), it.maxZoom.toInt()).takeIf { spec ->
                spec.minZoom.toDouble() == it.minZoom && spec.maxZoom.toDouble() == it.maxZoom
            }
        }
        val pack = Pack(region, key, spec)
        packs[region.id] = pack
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = acceptStatus(pack, status)
            override fun onError(error: OfflineRegionError) {
                if (pack.deletion.requested) return
                pack.problem = classify(error.message)
                setActive(pack, false)
                scheduleRetry(pack.problem)
                scheduleReconcile()
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                if (pack.deletion.requested) return
                pack.problem = ChartPackProblem.Quota
                setActive(pack, false)
                scheduleReconcile()
                scheduleSummary()
            }
        })
        requestStatus(pack)
    }

    private fun requestStatus(pack: Pack) {
        pack.region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (status == null) onError(null) else acceptStatus(pack, status)
            }
            override fun onError(error: String?) {
                if (pack.deletion.requested) return
                pack.statusRead = true
                pack.problem = classify(error.orEmpty())
                scheduleReconcile()
                scheduleRetry(pack.problem)
            }
        })
    }

    private fun acceptStatus(pack: Pack, status: OfflineRegionStatus) {
        if (pack.deletion.requested) return
        val first = !pack.statusRead
        pack.statusRead = true
        val wasReady = pack.ready
        val previousProblem = pack.problem
        val previous = pack.status
        pack.status = status
        if (status.completedResourceCount > (previous?.completedResourceCount ?: 0)) {
            pack.lastProgress = SystemClock.elapsedRealtime()
            pack.lastStall = 0L
        }
        if (pack.ready || (pack.problem != ChartPackProblem.Quota && status.completedResourceCount > (previous?.completedResourceCount ?: 0))) {
            pack.problem = null
        }
        if (first || wasReady != pack.ready || previousProblem != pack.problem) scheduleReconcile()
        scheduleSummary()
    }

    private fun setActive(pack: Pack, active: Boolean) {
        if (pack.active == active) return
        pack.active = active
        if (active) pack.lastProgress = SystemClock.elapsedRealtime()
        pack.region.setDownloadState(if (active) OfflineRegion.STATE_ACTIVE else OfflineRegion.STATE_INACTIVE)
    }

    private fun delete(pack: Pack) {
        pack.problem = null
        setActive(pack, false)
        pack.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                packs.remove(pack.region.id)
                // Releasing an owned region can free tiles against the native quota.
                creationErrors.entries.removeAll { it.value == ChartPackProblem.Quota }
                packs.values.filter { it.problem == ChartPackProblem.Quota }.forEach { it.problem = null }
                scheduleReconcile()
            }
            override fun onError(error: String) {
                pack.deletion.failed(classify(error, ChartPackProblem.Storage))
                scheduleRetry(pack.deletion.error)
            }
        })
    }

    private fun classify(message: String, fallback: ChartPackProblem = ChartPackProblem.Download): ChartPackProblem =
        when {
            listOf("quota", "tile count limit").any { message.contains(it, ignoreCase = true) } -> ChartPackProblem.Quota
            listOf("disk", "sqlite", "storage", "space", "full").any { message.contains(it, ignoreCase = true) } -> ChartPackProblem.Storage
            else -> fallback
        }

    private fun scheduleRetry(problem: ChartPackProblem?) {
        scheduleSummary()
        if (problem == ChartPackProblem.Quota) return
        if (!retryPending) {
            retryPending = true
            main.postDelayed(retryOperations, 60_000)
        }
    }

    private fun scheduleSummary() {
        if (!publishPending) {
            publishPending = true
            main.postDelayed(publish, 250)
        }
    }

    private fun publishSummary() {
        val live = packs.values.filterNot { it.deletion.requested }
        val errors = packs.values.filterNot { it.key in deferred }.mapNotNull { it.deletion.error ?: it.problem } +
            creationErrors.filterKeys { it !in deferred }.values + listOfNotNull(storageError)
        val quota = deferred.takeUnless { removed }.orEmpty()
        val empty = removalComplete(loaded, packs.size, creating.size)
        state.value = ChartPackSummary(
            total = if (removed) packs.size + creating.size else if (loaded) wanted.size else desired.orEmpty().size,
            ready = live.count { it.ready }, failed = errors.size + quota.size,
            bytes = packs.values.sumOf { it.status?.completedResourceSize ?: 0L },
            paused = paused, dataSaver = dataSaver, inputsUnavailable = keepExistingStations, removed = removed && empty, removing = removed && !empty,
            problem = if (quota.isNotEmpty()) ChartPackProblem.Quota else errors.minByOrNull { it.ordinal },
            canRetry = errors.isNotEmpty(),
        )
    }
}
