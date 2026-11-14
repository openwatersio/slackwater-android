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

enum class TideDownloadState { QUEUED, DOWNLOADING, WAITING_FOR_NETWORK, DATA_SAVER, RETRY }

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
    private val fit: suspend (StationIdentity, Instant, Boolean) -> Unit,
    private val fetch: suspend (StationIdentity, Instant) -> List<TidePoint>,
) {
    private val lock = Mutex()
    private val transfer = Mutex()
    private val fits = Mutex()
    private val startedActivities = MutableStateFlow(0)
    fun activityStarted() = startedActivities.update { it + 1 }
    fun activityStopped() = startedActivities.update { (it - 1).coerceAtLeast(0) }

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
                    for (id in jobs.keys.toList() - requested) stop(id)
                    for (id in referenceJobs.toList() - references) stop(id)
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
    }

    private suspend fun liveStation(id: StationId): StationIdentity? =
        (catalog.resolve(id) as? StationResolution.Live)?.identity?.takeIf { it.kind == StationKind.CanadianTide || (it.kind == StationKind.CanadianCurrent && currentGate(id)) }

    suspend fun request(id: StationId) = lock.withLock {
        val station = liveStation(id) ?: return@withLock
        if (currentGate(id)) intents.requestDownloads(listOf(id)) else intents.requestTide(id)
        if (states.value[id] == TideDownloadState.RETRY) stop(id)
        if (id !in jobs) start(station, full = true, manual = true)
    }

    suspend fun cancel(id: StationId) = lock.withLock {
        release(id)
        if (id !in referenceJobs) stop(id)
    }

    suspend fun cancelFromManager(id: StationId) = lock.withLock {
        if (id in referencePorts) intents.cancelReferenceAndTide(id) else release(id)
        stop(id)
    }

    private suspend fun release(id: StationId) {
        if (currentGate(id)) intents.releaseDownloadRequests(listOf(id)) else intents.cancelTide(id)
    }

    private fun stop(id: StationId) {
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

    private suspend fun finish(station: StationIdentity, previewOnly: Boolean = false, failed: Boolean = false, referenceOnly: Boolean = false) = lock.withLock {
        val id = station.id
        currentCoroutineContext().ensureActive()
        // A full request can arrive while a restored preview is downloading.
        if (previewOnly && id in intents.state.first().getOrThrow().tideFitRequested) {
            stop(id)
            start(station, full = true, manual = true)
            return@withLock
        }
        if (id in referencePorts && (referenceOnly || !previewOnly)) {
            intents.cancelReferenceAndTide(id)
        } else release(id)
        stop(id)
        if (failed) status(id, TideDownloadState.RETRY)
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

    private fun start(station: StationIdentity, full: Boolean, manual: Boolean = false, referenceOnly: Boolean = false) {
        val id = station.id
        if (referenceOnly) referenceJobs.add(id)
        status(id, TideDownloadState.QUEUED)
        jobs[id] = scope.launch {
            combine(startedActivities, network) { count, paused ->
                if (count == 0) TideDownloadState.QUEUED else paused
            }.distinctUntilChanged().collectLatest { paused ->
                var backoff = 60_000L
                while (true) {
                    val recoveryAtStart = networkRecovery.value
                    try {
                        if (referenceOnly) {
                            val now = Instant.now()
                            val ready = predictions.load(id, TimeSpan(now, now), PredictionPurpose.DISPLAY) as? PredictionResult.Ready
                            if (ready?.capabilities == StationCapabilities.FITTED_TIDE) {
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
                        if (!referenceOnly && !available && station.kind == StationKind.CanadianTide) download(station)
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
                    }, referencePortIds = { derivedGatesByReference(app.assets).keys }, fit = { station, now, manual ->
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
