package io.openwaters.slackwater.android.data

import android.content.Context
import io.openwaters.slackwater.android.core.downloads.IwlsOnlineGateSource
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.core.timeline.TimelineWindow
import io.openwaters.slackwater.android.map.watchDownloadNetwork
import io.openwaters.slackwater.iwls.IwlsException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class OnlineDownloadState { DOWNLOADING, WAITING_TO_RETRY, FAILED, OFFLINE }

internal class OnlineGateDownloads(
    private val stationFor: suspend (StationId) -> StationIdentity?,
    private val coverage: suspend (StationId) -> List<TimeSpan>,
    private val download: suspend (StationIdentity, Instant, Instant) -> Unit,
    val online: StateFlow<Boolean>,
    val restricted: StateFlow<Boolean>,
    private val clock: () -> Instant = Instant::now,
    private val retryDelayMillis: Long = 60_000,
) {
    private val pending = ConcurrentHashMap<Pair<StationId, LocalDate>, CompletableDeferred<Boolean?>>()
    private val retryWake = ConcurrentHashMap<StationId, MutableStateFlow<Long>>()
    private val transfer = Mutex()
    private val prefetchLane = Mutex()
    private val holds = ConcurrentHashMap<StationId, Mutex>()
    private val failedDay = ConcurrentHashMap<StationId, LocalDate>()
    private val refreshedDay = ConcurrentHashMap<StationId, LocalDate>()
    private val mutableStates = MutableStateFlow<Map<Pair<StationId, LocalDate>, OnlineDownloadState>>(emptyMap())
    val weekStates = mutableStates.asStateFlow()

    private fun status(id: StationId, day: LocalDate, state: OnlineDownloadState?) {
        val key = id to day
        mutableStates.update { if (state == null) it - key else it + (key to state) }
    }

    private fun reconnected() {
        failedDay.clear()
        mutableStates.update { states -> states.filterValues { it != OnlineDownloadState.OFFLINE } }
    }

    private enum class Attempt { COVERED, UNAVAILABLE, RETRY }

    private suspend fun attempt(
        station: StationIdentity, day: LocalDate, manual: Boolean, refresh: Boolean, last: Boolean,
    ): Attempt {
        val id = station.id
        val zone = station.currentGateZone()
        val anchor = day.atStartOfDay(zone).toInstant()
        val span = TimelineWindow.fixed(day, zone)
        val covered = coverage(id).firstOrNull { span.start in it && span.end in it }
        val quiet = covered != null && !manual
        if (covered != null && !manual && (!refresh || restricted.value || !online.value || refreshedDay[id] == day ||
                covered.end >= anchor.plus(Duration.ofDays(30)).minus(Duration.ofHours(12)))) return Attempt.COVERED
        if (!online.value) {
            status(id, day, OnlineDownloadState.OFFLINE)
            return if (covered == null) Attempt.UNAVAILABLE else Attempt.COVERED
        }
        if (!manual && covered == null && failedDay[id] == day) return Attempt.UNAVAILABLE
        try {
            transfer.withLock {
                if (quiet) refreshedDay[id] = day
                if (!quiet) status(id, day, OnlineDownloadState.DOWNLOADING)
                download(station, anchor, clock().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant())
            }
            if (coverage(id).none { span.start in it && span.end in it }) {
                throw IwlsException.Permanent("Downloaded predictions do not cover this week")
            }
            failedDay.remove(id)
            status(id, day, null)
            return Attempt.COVERED
        } catch (cancelled: CancellationException) {
            if (quiet) refreshedDay.remove(id, day)
            throw cancelled
        } catch (failure: Exception) {
            if (quiet) return Attempt.COVERED
            if (failure !is IwlsException && failure !is IOException) throw failure
            if (last || failure is IwlsException.Permanent) {
                failedDay[id] = day
                status(id, day, OnlineDownloadState.FAILED)
                return if (covered == null) Attempt.UNAVAILABLE else Attempt.COVERED
            }
            status(id, day, OnlineDownloadState.WAITING_TO_RETRY)
            return Attempt.RETRY
        }
    }

    private suspend fun request(
        station: StationIdentity, day: LocalDate, manual: Boolean = false,
        refresh: Boolean = false, background: Boolean = false,
    ): Boolean {
        val id = station.id
        val wake = retryWake.computeIfAbsent(id) { MutableStateFlow(0L) }
        if (manual) wake.update { it + 1 }
        val key = id to day
        val hold = holds.computeIfAbsent(id) { Mutex() }
        while (true) {
            val generation = wake.value
            val completion = CompletableDeferred<Boolean?>()
            var active: CompletableDeferred<Boolean?>? = null
            var result: Boolean? = null
            suspend fun firstAttempt(): Attempt? {
                active = pending.putIfAbsent(key, completion)
                if (active != null) return null
                return hold.withLock { attempt(station, day, manual, refresh, last = false) }
            }
            try {
                // Background candidates claim their week only after admission. A tap can
                // claim a week further down the queue while the current transfer finishes.
                val first = if (background) prefetchLane.withLock { firstAttempt() } else firstAttempt()
                if (first == null) {
                    val joined = requireNotNull(active).await()
                    if (joined != null && (!manual || joined)) return joined
                    continue
                }
                val final = if (first != Attempt.RETRY) first else {
                    if (withTimeoutOrNull(retryDelayMillis) { wake.first { it != generation } } != null) {
                        result = false
                        return false
                    }
                    suspend fun retry() = hold.withLock { attempt(station, day, manual, refresh, last = true) }
                    if (background) prefetchLane.withLock { retry() } else retry()
                }
                result = final == Attempt.COVERED
                return result
            } catch (cancelled: CancellationException) {
                if (pending[key] === completion) status(id, day, null)
                throw cancelled
            } catch (_: Exception) {
                failedDay[id] = day
                status(id, day, OnlineDownloadState.FAILED)
                result = false
                return false
            } finally {
                if (pending.remove(key, completion)) completion.complete(result)
            }
        }
    }

    suspend fun fetch(id: StationId, day: LocalDate, manual: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val station = stationFor(id) ?: return@withContext false
        request(station, day, manual)
    }

    suspend fun refreshIfStale(id: StationId, day: LocalDate) = withContext(Dispatchers.IO) {
        stationFor(id)?.let { request(it, day, refresh = true) }
    }

    suspend fun prefetch(ids: Collection<StationId>): Duration = withContext(Dispatchers.IO) {
        val stations = ids.mapNotNull { stationFor(it) }
        val now = clock()
        val nextDay = stations.minOfOrNull { station ->
            val local = now.atZone(station.currentGateZone())
            local.toLocalDate().plusDays(1).atStartOfDay(local.zone).toInstant()
        } ?: now.plus(Duration.ofDays(1))
        coroutineScope {
            for (station in stations) launch {
                val day = now.atZone(station.currentGateZone()).toLocalDate()
                request(station, day, refresh = true, background = true)
            }
        }
        Duration.between(clock(), nextDay)
    }

    suspend fun prefetchWhileStarted(candidates: Flow<Set<StationId>>) = coroutineScope {
        val jobs = mutableMapOf<StationId, Job>()
        candidates.distinctUntilChanged().collect { ids ->
            for (removed in jobs.keys.toSet() - ids) jobs.remove(removed)?.cancel()
            for (id in ids - jobs.keys) {
                jobs[id] = launch {
                    while (isActive) {
                        val limited = restricted.value
                        val untilTomorrow = prefetch(listOf(id)).toMillis().coerceAtLeast(1)
                        withTimeoutOrNull(untilTomorrow) { restricted.first { it != limited } }
                    }
                }
            }
        }
    }

    companion object {
        private var shared: OnlineGateDownloads? = null

        @Synchronized
        fun get(context: Context): OnlineGateDownloads {
            shared?.let { return it }
            val app = context.applicationContext
            val catalog = BundledStationCatalog(app.assets)
            val gates = BundledCurrentGates(app.assets)
            val store = OnlineWindowStore(ModelStore.get(app))
            val source = IwlsOnlineGateSource(app.cacheDir)
            val online = MutableStateFlow(false)
            val restricted = MutableStateFlow(false)
            val service = OnlineGateDownloads({ id ->
                if (gates[id]?.online == true) (catalog.resolve(id) as? StationResolution.Live)?.identity else null
            }, store::coverage, { station, anchor, today ->
                val result = source.fetch(station, anchor)
                store.save(station.id, OnlineWindowBlock(result.points, result.floodDirection, result.ebbDirection), today)
            }, online, restricted)
            watchDownloadNetwork(app) { available, limited, _ ->
                restricted.value = limited
                if (available && !online.value) service.reconnected()
                online.value = available
            }
            return service.also { shared = it }
        }
    }
}
