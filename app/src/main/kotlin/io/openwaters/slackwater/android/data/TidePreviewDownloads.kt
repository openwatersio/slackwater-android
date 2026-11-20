package io.openwaters.slackwater.android.data

import android.content.Context
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.android.core.downloads.IwlsTidePreviewSource
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.map.watchDownloadNetwork
import io.openwaters.slackwater.iwls.IwlsException
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class TideDownloadState { QUEUED, DOWNLOADING, WAITING_FOR_NETWORK, DATA_SAVER, WAITING_TO_RETRY, RETRY }

internal class TideDownloadNetwork {
    private val pauseState = MutableStateFlow<TideDownloadState?>(TideDownloadState.WAITING_FOR_NETWORK)
    private val validationCount = MutableStateFlow(0L)
    private var wasValidated = false
    val paused = pauseState.asStateFlow()
    val recovery = validationCount.asStateFlow()

    fun update(online: Boolean, restricted: Boolean, validated: Boolean) {
        pauseState.value = when {
            !online -> TideDownloadState.WAITING_FOR_NETWORK
            restricted -> TideDownloadState.DATA_SAVER
            else -> null
        }
        if (validated && !wasValidated) validationCount.value++
        wasValidated = validated
    }
}

internal class TidePreviewDownloads(
    private val scope: CoroutineScope,
    private val intents: DownloadIntentStore,
    private val models: ModelStore,
    private val catalog: StationCatalog,
    private val network: StateFlow<TideDownloadState?>,
    private val predictions: PredictionRepository,
    private val networkRecovery: StateFlow<Long> = MutableStateFlow(0L),
    private val currentGate: (StationId) -> Boolean = { false },
    referencePortIds: () -> Set<StationId> = { emptySet() },
    private val gateReferences: () -> Map<StationId, StationId> = { emptyMap() },
    private val fit: suspend (StationIdentity, Instant, Boolean) -> Unit,
    private val fetch: suspend (StationIdentity, Instant) -> List<TidePoint>,
) {
    private val lock = Mutex()
    private val transfer = Mutex()
    private val fits = Mutex()
    /** Started activities plus a running background job. Transfers run only while one is open. */
    private val runners = MutableStateFlow(0)
    fun activityStarted() = runners.update { it + 1 }
    fun activityStopped() = runners.update { (it - 1).coerceAtLeast(0) }
    fun backgroundStarted() = activityStarted()
    fun backgroundStopped() = activityStopped()

    /** Why transfers are paused for the network, or null when they may run. */
    val networkPause: StateFlow<TideDownloadState?> get() = network
    /** A fitted current gate, or a reference tide that derived current gates are fitted through. */
    fun isCurrentWork(id: StationId) = currentGate(id) || id in referencePorts

    private val mutableProgress = MutableStateFlow<AutomaticProgress?>(null)
    /** The shared automatic loop's candidates and what remains of them; null while the loop is stopped. */
    val progress = mutableProgress.asStateFlow()

    val openStation = MutableStateFlow<StationId?>(null)
    private val automaticReferences by lazy(gateReferences)

    /** Rows whose failure or backoff belongs to a reference-only fit, so the manager retries that fit. */
    private val referenceRows = mutableSetOf<StationId>()
    private val retries = AutomaticFitRetries()
    /** Candidates from each caller of [fitWhileStarted]; one shared loop fits their union. */
    private val candidateSources = MutableStateFlow<Map<Any, List<StationId>>>(emptyMap())
    /** Targets of the shared automatic loop, under [lock]; null while it is stopped. */
    private var wantedTargets: Set<StationId>? = null

    /**
     * Keep the automatic loop fitting [candidates] until the caller is cancelled. Callers overlap
     * when a second activity instance runs (multi-window, a calendar launch) or one activity starts
     * another. Each one adds its candidates to the single loop instead of running a loop of its own.
     */
    suspend fun fitWhileStarted(candidates: Flow<List<StationId>>) {
        val source = Any()
        try {
            candidates.collect { ids -> candidateSources.update { it + (source to ids) } }
            awaitCancellation()
        } finally {
            candidateSources.update { it - source }
        }
    }

    private fun clearDeferrals(where: (StationId) -> Boolean) {
        for ((id, state) in states.value) {
            if (state == TideDownloadState.WAITING_TO_RETRY && id !in jobs && where(id)) stop(id)
        }
    }

    /** Clear every backoff after a network recovery; their rows go with them. Called under [lock]. */
    private fun observeRecovery() {
        if (retries.observeRecovery(networkRecovery.value)) clearDeferrals { true }
    }

    private suspend fun runAutomaticLoop() {
        val candidates = candidateSources.map { it.values.flatten().distinct() }.distinctUntilChanged()
        try {
            coroutineScope { fitCandidates(candidates) }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    wantedTargets = null
                    // Nothing retries a deferral once the loop stops; the next loop shows it again while due.
                    clearDeferrals { true }
                }
            }
            mutableProgress.value = null
        }
    }

    private suspend fun CoroutineScope.fitCandidates(candidates: Flow<List<StationId>>) {
        // publish() reads these from the selection collector while the loop writes them.
        val finished: MutableSet<StationId> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        // Candidates the loop passed over because they cannot be fitted or were cancelled.
        val skipped: MutableSet<StationId> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        // Finished candidates whose fit failed for good; they are done but not ready.
        val failed: MutableSet<StationId> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val progressLock = Any()
        var latest = emptyList<StationId>()
        // Each publish reads the latest candidates and sets under one lock, so an older snapshot never lands last.
        fun publish(ids: List<StationId>? = null) = synchronized(progressLock) {
            if (ids != null) latest = ids
            val pending = latest.filterTo(mutableSetOf()) { it !in finished }
            val now = android.os.SystemClock.elapsedRealtime()
            mutableProgress.value = AutomaticProgress(latest.toSet() - skipped, pending,
                pending.filterTo(mutableSetOf()) { retries.due(automaticReferences[it] ?: it) > now }, latest.toSet().intersect(failed))
        }
        val wanted = candidates.onEach { ids ->
            val targets = ids.groupBy { automaticReferences[it] ?: it }
            lock.withLock {
                currentCoroutineContext().ensureActive()
                wantedTargets = targets.keys
                observeRecovery()
                // The backoff stays, so a station that flaps in and out of the selection is not refetched each time.
                clearDeferrals { it !in targets }
                // Restored here, not in the loop: the loop misses a removal and re-add that conflate to an equal list.
                val now = android.os.SystemClock.elapsedRealtime()
                for ((target, sources) in targets) {
                    if (target in jobs || target in states.value || retries.due(target) <= now) continue
                    if (target !in sources) referenceRows.add(target)
                    status(target, TideDownloadState.WAITING_TO_RETRY)
                }
            }
            publish(ids)
        }.stateIn(this)
        while (currentCoroutineContext().isActive) {
            val pending = wanted.first { ids -> ids.any { it !in finished } }
            val recovery = networkRecovery.value
            lock.withLock { observeRecovery() }
            val now = android.os.SystemClock.elapsedRealtime()
            val id = pending.firstOrNull { it !in finished && retries.due(automaticReferences[it] ?: it) <= now }
            if (id == null) {
                val wake = pending.filter { it !in finished }.minOf { retries.due(automaticReferences[it] ?: it) }
                withTimeoutOrNull((wake - now).coerceAtLeast(1)) {
                    combine(wanted, networkRecovery) { ids, generation -> ids != pending || generation != recovery }.first { it }
                }
                continue
            }
            val target = automaticReferences[id] ?: id
            val station = liveStation(target)
            val job = lock.withLock {
                if (station == null || target in intents.state.first().getOrNull()?.cancelled.orEmpty()) {
                    // A row waiting for this target would otherwise outlast the skip.
                    clearDeferrals { it == target }
                    skipped.add(id)
                    return@withLock null
                }
                if (target in jobs) return@withLock null
                automaticJobs.add(target)
                start(station, full = true, referenceOnly = target != id, automatic = true)
                jobs.getValue(target)
            }
            if (job == null) {
                finished.add(id)
                publish()
                continue
            }
            try {
                coroutineScope {
                    val removal = launch {
                        combine(wanted, states) { selection, statuses ->
                            id !in selection || statuses[target] == TideDownloadState.RETRY
                        }.first { it }
                        job.cancel()
                    }
                    job.join()
                    removal.cancel()
                }
                lock.withLock {
                    // wantedTargets, unlike wanted.value, is current under this lock.
                    if (jobs[target] === job && states.value[target] == TideDownloadState.RETRY && target in wantedTargets.orEmpty()) {
                        retries.defer(target, android.os.SystemClock.elapsedRealtime())
                        stop(target)
                        if (id != target) referenceRows.add(target)
                        status(target, TideDownloadState.WAITING_TO_RETRY)
                    } else if (id in wanted.value) {
                        retries.clear(target)
                        // A permanent failure leaves its row at RETRY.
                        if (states.value[target] == TideDownloadState.RETRY) failed.add(id)
                        finished.add(id)
                    }
                }
                publish()
            } finally {
                withContext(NonCancellable) {
                    lock.withLock { if (jobs[target] === job) stop(target) }
                }
            }
        }
    }

    private val automaticJobs = mutableSetOf<StationId>()
    private val jobs = mutableMapOf<StationId, Job>()
    private val referencePorts by lazy(referencePortIds)
    private val referenceJobs = mutableSetOf<StationId>()
    private val mutableStates = MutableStateFlow<Map<StationId, TideDownloadState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    init {
        scope.launch {
            intents.state.onEach { observation ->
                if (observation.isFailure) return@onEach
                lock.withLock {
                    // A queued observation may predate an explicit cancel or retry that held this lock.
                    val intent = intents.state.first().getOrThrow()
                    val currents = intent.requested.filter(currentGate)
                    val references = intent.requested.intersect(referencePorts)
                    val requested = intent.previewRequested + currents + references
                    for (id in jobs.keys.toList() - requested - automaticJobs) stop(id)
                    for (id in referenceJobs.toList() - references - automaticJobs) stop(id)
                    for (id in requested - jobs.keys) {
                        val station = liveStation(id)
                        if (station == null) {
                            release(id)
                            if (id in references) intents.releaseDownloadRequests(listOf(id))
                        } else if (id in references && id !in intent.previewRequested) {
                            start(station, full = true, referenceOnly = true)
                        } else start(station, full = id in currents || id in intent.tideFitRequested)
                    }
                }
            }.retryAlertReads().collect()
        }
        scope.launch(Dispatchers.IO) {
            // collectLatest joins a stopping loop before the next one starts, so loops never overlap.
            candidateSources.map { it.isNotEmpty() }.distinctUntilChanged().collectLatest { active ->
                if (active) runAutomaticLoop()
            }
        }
    }

    private suspend fun liveStation(id: StationId): StationIdentity? =
        (catalog.resolve(id) as? StationResolution.Live)?.identity?.takeIf { it.kind == StationKind.CanadianTide || (it.kind == StationKind.CanadianCurrent && currentGate(id)) }

    suspend fun request(id: StationId) = lock.withLock { requestStation(id) }

    /** Retry a failed or waiting row, restarting the reference-only fit when the row is one. */
    suspend fun retry(id: StationId) = lock.withLock {
        if (id in referenceRows || id in referenceJobs) requestReferenceLocked(id) else requestStation(id)
    }

    private suspend fun requestStation(id: StationId) {
        val station = liveStation(id) ?: return
        if (currentGate(id)) intents.requestDownloads(listOf(id)) else intents.requestTide(id)
        retries.clear(id)
        if (id in automaticJobs || id in referenceJobs || states.value[id] == TideDownloadState.RETRY) stop(id)
        if (id !in jobs) start(station, full = true, manual = true)
    }

    suspend fun requestReference(id: StationId) = lock.withLock { requestReferenceLocked(id) }

    private suspend fun requestReferenceLocked(id: StationId) {
        val station = liveStation(id) ?: return
        if (id !in referencePorts) return
        intents.requestDownloads(listOf(id))
        retries.clear(id)
        // A failed manual reference sleeps in its own backoff; an explicit retry restarts it now.
        if (id in automaticJobs || states.value[id] == TideDownloadState.RETRY) stop(id)
        if (id !in jobs) start(station, full = true, manual = true, referenceOnly = true)
    }

    suspend fun cancel(id: StationId) = lock.withLock {
        val general = currentGate(id) || id in referenceJobs || id in referenceRows
        intents.cancelSelection(id, general = general, tide = !general)
        retries.clear(id)
        stop(id)
    }

    suspend fun cancelFromManager(id: StationId) = lock.withLock {
        val current = currentGate(id)
        intents.cancelSelection(id, general = current || id in referencePorts, tide = !current)
        retries.clear(id)
        stop(id)
    }

    private suspend fun release(id: StationId) {
        if (currentGate(id)) intents.releaseDownloadRequests(listOf(id)) else intents.cancelTide(id)
    }

    private fun stop(id: StationId) {
        referenceRows.remove(id)
        automaticJobs.remove(id)
        referenceJobs.remove(id)
        jobs.remove(id)?.cancel()
        mutableStates.update { it - id }
    }

    private fun status(id: StationId, state: TideDownloadState) {
        mutableStates.update { it + (id to state) }
    }

    private suspend fun activeStatus(id: StationId, state: TideDownloadState) = lock.withLock {
        currentCoroutineContext().ensureActive()
        status(id, state)
    }

    private suspend fun alreadyAvailable(id: StationId): Boolean {
        val now = Instant.now()
        val result = predictions.load(id, TimeSpan(now, now), PredictionPurpose.DISPLAY)
        return result is PredictionResult.Ready && now in result.coverage
    }

    private suspend fun finish(station: StationIdentity, previewOnly: Boolean = false, failed: Boolean = false, referenceOnly: Boolean = false, previewFailure: Boolean = false) = lock.withLock {
        val id = station.id
        currentCoroutineContext().ensureActive()
        if (id in automaticJobs) {
            stop(id)
            if (failed) failedStatus(id, referenceOnly)
            return@withLock
        }
        // A full request can arrive while a restored preview is downloading.
        if (previewOnly && id in intents.state.first().getOrThrow().tideFitRequested) {
            stop(id)
            start(station, full = true, manual = true)
            return@withLock
        }
        if (referenceOnly) {
            intents.releaseDownloadRequests(listOf(id))
        } else if (id in referencePorts && !previewOnly && !previewFailure) {
            intents.cancelReferenceAndTide(id)
        } else release(id)
        stop(id)
        if (failed) failedStatus(id, referenceOnly)
    }

    private fun failedStatus(id: StationId, referenceOnly: Boolean) {
        if (referenceOnly) referenceRows.add(id)
        status(id, TideDownloadState.RETRY)
    }

    private suspend fun download(station: StationIdentity) = transfer.withLock {
        activeStatus(station.id, TideDownloadState.DOWNLOADING)
        val samples = fetch(station, Instant.now())
        val record = JSONObject().put("samples", JSONArray().apply {
            for (point in samples) put(JSONObject().put("time", point.time.toString()).put("height", point.heightMetres))
        })
        currentCoroutineContext().ensureActive()
        models.write(station.id, ModelKind.TIDE_PREVIEW, record)
    }

    private fun start(station: StationIdentity, full: Boolean, manual: Boolean = false, referenceOnly: Boolean = false, automatic: Boolean = false) {
        val id = station.id
        referenceRows.remove(id)
        if (referenceOnly) referenceJobs.add(id)
        status(id, TideDownloadState.QUEUED)
        jobs[id] = scope.launch {
            combine(runners, network) { count, paused ->
                if (count == 0) TideDownloadState.QUEUED else paused
            }.distinctUntilChanged().collectLatest { paused ->
                var backoff = 60_000L
                while (true) {
                    val recoveryAtStart = networkRecovery.value
                    try {
                        if (referenceOnly || automatic) {
                            val now = Instant.now()
                            val ready = predictions.load(id, TimeSpan(now, now), PredictionPurpose.DISPLAY) as? PredictionResult.Ready
                            if (ready?.capabilities in setOf(StationCapabilities.FITTED_TIDE, StationCapabilities.FINAL_CURRENT, StationCapabilities.BOUNDED_CURRENT)) {
                                finish(station, referenceOnly = true)
                                return@collectLatest
                            }
                        }
                        val available = !referenceOnly && station.kind == StationKind.CanadianTide && alreadyAvailable(id)
                        if (available && !full) {
                            finish(station, previewOnly = true)
                            return@collectLatest
                        }
                        if (paused != null) {
                            activeStatus(id, paused)
                            return@collectLatest
                        }
                        activeStatus(id, TideDownloadState.QUEUED)
                        if (!referenceOnly && !available && station.kind == StationKind.CanadianTide) {
                            try {
                                download(station)
                            } catch (_: IwlsException.Permanent) {
                                finish(station, failed = true, previewFailure = true)
                                return@collectLatest
                            }
                        }
                        if (full) {
                            fits.withLock {
                                activeStatus(id, TideDownloadState.DOWNLOADING)
                                fit(station, Instant.now(), manual)
                            }
                        }
                        finish(station, previewOnly = !full, referenceOnly = referenceOnly)
                        return@collectLatest
                    } catch (_: IwlsException.Permanent) {
                        finish(station, failed = true, referenceOnly = referenceOnly)
                        return@collectLatest
                    } catch (_: io.openwaters.slackwater.HarmonicFitException) {
                        finish(station, failed = true, referenceOnly = referenceOnly)
                        return@collectLatest
                    } catch (_: IwlsException.Transient) {
                        activeStatus(id, TideDownloadState.RETRY)
                    } catch (_: IOException) {
                        activeStatus(id, TideDownloadState.RETRY)
                    }
                    val recovered = withTimeoutOrNull(backoff) { networkRecovery.first { it != recoveryAtStart } } != null
                    backoff = if (recovered) 60_000L else (backoff * 2).coerceAtMost(15 * 60_000L)
                }
            }
        }
    }

    companion object {
        private var shared: TidePreviewDownloads? = null

        @Synchronized
        fun get(context: Context): TidePreviewDownloads {
            val app = context.applicationContext
            return shared ?: run {
                val network = TideDownloadNetwork()
                watchDownloadNetwork(app, update = network::update)
                val source = IwlsTidePreviewSource(app.cacheDir)
                val models = ModelStore.get(app)
                val gates = BundledCurrentGates(app.assets)
                TidePreviewDownloads(CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    UserStateStore.get(app).downloads, models, BundledStationCatalog(app.assets),
                    network.paused, AndroidPredictionRepository(app), network.recovery, currentGate = { id ->
                        gates[id]?.allowsFinalFit(id) == true
                    }, gateReferences = { derivedGatesByReference(app.assets).flatMap { (reference, gates) -> gates.map { it to reference } }.toMap() }, referencePortIds = { derivedGatesByReference(app.assets).keys }, fit = { station, now, manual ->
                        if (station.kind == StationKind.CanadianCurrent) {
                            val gate = requireNotNull(gates[station.id])
                            val final = source.fitCurrent(station, gate, now, manual) { models.publishCurrentFit(gate, it) }
                            models.publishCurrentFit(gate, final)
                        } else models.fitCanadianTide(station.id, now, source.stationZone(station), source.fitChunks(station, now, manual))
                    }, fetch = source::fetch)
            }.also { shared = it }
        }
    }
}

/**
 * [stations] the automatic loop wants and can fit; [pending] those it has not finished; [waiting]
 * the pending ones sitting out a retry backoff; [failed] the finished ones whose fit failed for good.
 */
internal data class AutomaticProgress(
    val stations: Set<StationId>,
    val pending: Set<StationId>,
    val waiting: Set<StationId>,
    val failed: Set<StationId> = emptySet(),
)

internal class AutomaticFitRetries {
    private data class Retry(val delay: Long, val due: Long)
    private val stations = mutableMapOf<StationId, Retry>()

    @Synchronized fun due(id: StationId): Long = stations[id]?.due ?: 0L

    @Synchronized fun defer(id: StationId, now: Long) {
        val delay = stations[id]?.let { (it.delay * 2).coerceAtMost(900_000L) } ?: 60_000L
        stations[id] = Retry(delay, now + delay)
    }

    @Synchronized fun clear(id: StationId) { stations.remove(id) }

    private var generation = -1L
    /** True when [value] is a new recovery and every backoff was cleared. */
    @Synchronized fun observeRecovery(value: Long): Boolean {
        if (value <= generation) return false
        stations.clear()
        generation = value
        return true
    }
}
