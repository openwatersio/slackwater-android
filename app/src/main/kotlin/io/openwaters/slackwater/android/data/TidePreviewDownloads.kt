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

internal class TidePreviewDownloads(
    private val scope: CoroutineScope,
    private val intents: DownloadIntentStore,
    private val models: ModelStore,
    private val catalog: StationCatalog,
    private val network: StateFlow<TideDownloadState?>,
    private val predictions: PredictionRepository,
    private val networkRecovery: StateFlow<Long> = MutableStateFlow(0L),
    private val fetch: suspend (StationIdentity, Instant) -> List<TidePoint>,
) {
    private val lock = Mutex()
    private val transfer = Mutex()
    private val startedActivities = MutableStateFlow(0)
    fun activityStarted() = startedActivities.update { it + 1 }
    fun activityStopped() = startedActivities.update { (it - 1).coerceAtLeast(0) }

    private val jobs = mutableMapOf<StationId, Job>()
    private val mutableStates = MutableStateFlow<Map<StationId, TideDownloadState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    init {
        scope.launch {
            intents.state.onEach { observation ->
                if (observation.isFailure) return@onEach
                lock.withLock {
                    // A queued observation may predate an explicit cancel or retry that held this lock.
                    val requested = intents.state.first().getOrThrow().previewRequested
                    for (id in jobs.keys.toList() - requested) stop(id)
                    for (id in requested - jobs.keys) {
                        val station = liveStation(id)
                        if (station == null) intents.releasePreviews(listOf(id)) else start(station)
                    }
                }
            }.retryAlertReads().collect()
        }
    }

    private suspend fun liveStation(id: StationId): StationIdentity? =
        (catalog.resolve(id) as? StationResolution.Live)?.identity?.takeIf { it.kind == StationKind.CanadianTide }

    suspend fun request(id: StationId) = lock.withLock {
        val station = liveStation(id) ?: return@withLock
        intents.requestPreviews(listOf(id))
        if (states.value[id] == TideDownloadState.RETRY) stop(id)
        if (id !in jobs) start(station)
    }

    suspend fun cancel(id: StationId) = lock.withLock {
        intents.releasePreviews(listOf(id))
        stop(id)
    }

    private fun stop(id: StationId) {
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

    private suspend fun finish(id: StationId, record: JSONObject? = null, failed: Boolean = false) = lock.withLock {
        currentCoroutineContext().ensureActive()
        if (record != null) models.write(id, ModelKind.TIDE_PREVIEW, record)
        intents.releasePreviews(listOf(id))
        stop(id)
        if (failed) status(id, TideDownloadState.RETRY)
    }

    private suspend fun download(station: StationIdentity) = transfer.withLock {
        activeStatus(station.id, TideDownloadState.DOWNLOADING)
        val samples = try {
            fetch(station, Instant.now())
        } catch (_: IwlsException.Permanent) {
            finish(station.id, failed = true)
            return@withLock
        }
        val record = JSONObject().put("samples", JSONArray().apply {
            for (point in samples) put(JSONObject().put("time", point.time.toString()).put("height", point.heightMetres))
        })
        finish(station.id, record)
    }

    private fun start(station: StationIdentity) {
        val id = station.id
        status(id, TideDownloadState.QUEUED)
        jobs[id] = scope.launch {
            combine(startedActivities, network) { count, paused ->
                if (count == 0) TideDownloadState.QUEUED else paused
            }.distinctUntilChanged().collectLatest { paused ->
                var backoff = 60_000L
                while (true) {
                    val recoveryAtStart = networkRecovery.value
                    try {
                        if (alreadyAvailable(id)) {
                            finish(id)
                            return@collectLatest
                        }
                        if (paused != null) {
                            activeStatus(id, paused)
                            return@collectLatest
                        }
                        activeStatus(id, TideDownloadState.QUEUED)
                        download(station)
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
                val network = MutableStateFlow<TideDownloadState?>(TideDownloadState.WAITING_FOR_NETWORK)
                val recovery = MutableStateFlow(0L)
                var wasValidated = false
                watchDownloadNetwork(app) { online, restricted, validated ->
                    if (validated && !wasValidated) recovery.value++
                    wasValidated = validated
                    network.value = when {
                        !online -> TideDownloadState.WAITING_FOR_NETWORK
                        restricted -> TideDownloadState.DATA_SAVER
                        else -> null
                    }
                }
                TidePreviewDownloads(CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    UserStateStore.get(app).downloads, ModelStore.get(app), BundledStationCatalog(app.assets),
                    network, AndroidPredictionRepository(app), recovery, IwlsTidePreviewSource(app.cacheDir)::fetch)
            }.also { shared = it }
        }
    }
}
