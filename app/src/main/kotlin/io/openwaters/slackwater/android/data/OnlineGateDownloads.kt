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
import java.time.ZoneId
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
    private val mutableStates = MutableStateFlow<Map<StationId, OnlineDownloadState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    private fun status(id: StationId, state: OnlineDownloadState?) = mutableStates.update {
        if (state == null) it - id else it + (id to state)
    }

    private enum class Attempt { COVERED, UNAVAILABLE, RETRY }

    private suspend fun attempt(
        station: StationIdentity, day: LocalDate, manual: Boolean, refresh: Boolean, last: Boolean,
    ): Attempt {
        val id = station.id
        val zone = ZoneId.of(station.timezone ?: "UTC")
        val anchor = day.atStartOfDay(zone).toInstant()
        val span = TimelineWindow.fixed(day, zone)
        val covered = coverage(id).firstOrNull { span.start in it && span.end in it }
        val quiet = covered != null && !manual
        if (covered != null && !manual && (!refresh || restricted.value || !online.value || refreshedDay[id] == day ||
                covered.end >= anchor.plus(Duration.ofDays(30)).minus(Duration.ofHours(12)))) return Attempt.COVERED
        if (!online.value) {
            status(id, OnlineDownloadState.OFFLINE)
            return if (covered == null) Attempt.UNAVAILABLE else Attempt.COVERED
        }
        if (!manual && covered == null && failedDay[id] == day) return Attempt.UNAVAILABLE
        if (quiet) refreshedDay[id] = day
        try {
            transfer.withLock {
                if (!quiet) status(id, OnlineDownloadState.DOWNLOADING)
                download(station, anchor, clock().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant())
            }
            if (coverage(id).none { span.start in it && span.end in it }) {
                throw IwlsException.Transient("Downloaded predictions do not cover this week")
            }
            failedDay.remove(id)
            status(id, null)
            return Attempt.COVERED
        } catch (cancelled: CancellationException) {
            if (!quiet) status(id, null)
            throw cancelled
        } catch (failure: Exception) {
            if (failure !is IwlsException && failure !is IOException) throw failure
            if (quiet) return Attempt.COVERED
            if (last || failure is IwlsException.Permanent) {
                failedDay[id] = day
                status(id, OnlineDownloadState.FAILED)
                return if (covered == null) Attempt.UNAVAILABLE else Attempt.COVERED
            }
            status(id, OnlineDownloadState.WAITING_TO_RETRY)
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
        while (true) {
            val completion = CompletableDeferred<Boolean?>()
            val active = pending.putIfAbsent(key, completion)
            if (active != null) {
                val result = active.await()
                if (result != null && (!manual || result)) return result
                continue
            }
            var result: Boolean? = null
            try {
                result = runCycle(station, day, manual, refresh, background, wake)
                return result
            } finally {
                pending.remove(key, completion)
                completion.complete(result)
            }
        }
    }

    private suspend fun runCycle(
        station: StationIdentity, day: LocalDate, manual: Boolean, refresh: Boolean,
        background: Boolean, wake: StateFlow<Long>,
    ): Boolean {
        val id = station.id
        val generation = wake.value
        val hold = holds.computeIfAbsent(id) { Mutex() }
        repeat(2) { index ->
            suspend fun runAttempt() = hold.withLock { attempt(station, day, manual, refresh, last = index == 1) }
            // A background gate waits for admission before taking its station lock. A tap can
            // acquire that lock while another gate is transferring or waiting out a backoff.
            val outcome = if (background) prefetchLane.withLock { runAttempt() } else runAttempt()
            when (outcome) {
                Attempt.COVERED -> return true
                Attempt.UNAVAILABLE -> return false
                Attempt.RETRY -> if (withTimeoutOrNull(retryDelayMillis) { wake.first { it != generation } } != null) return false
            }
        }
        return false
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
            val local = now.atZone(ZoneId.of(station.timezone ?: "UTC"))
            local.toLocalDate().plusDays(1).atStartOfDay(local.zone).toInstant()
        } ?: now.plus(Duration.ofDays(1))
        coroutineScope {
            for (station in stations) launch {
                val day = now.atZone(ZoneId.of(station.timezone ?: "UTC")).toLocalDate()
                request(station, day, refresh = true, background = true)
            }
        }
        Duration.between(clock(), nextDay)
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
                if (available && !online.value) service.failedDay.clear()
                online.value = available
            }
            return service.also { shared = it }
        }
    }
}
