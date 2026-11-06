package io.openwaters.slackwater.android.core.timeline

import io.openwaters.slackwater.CurrentEvent
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.TideExtreme
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.TideRatePoint
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToLong

data class TimelineDay(
    val date: LocalDate,
    val sunrise: Instant? = null,
    val sunset: Instant? = null,
    val solarNoon: Instant? = null,
    val moonrise: Instant? = null,
    val moonset: Instant? = null,
)

data class TimelineChunkData(
    val tidePoints: List<TidePoint> = emptyList(),
    val tideRates: List<TideRatePoint> = emptyList(),
    val tideEvents: List<TideExtreme> = emptyList(),
    val currentPoints: List<CurrentPoint> = emptyList(),
    val currentEvents: List<CurrentEvent> = emptyList(),
    val derivedSlacks: List<PredictionSeries.DerivedSlack> = emptyList(),
    val days: List<TimelineDay> = emptyList(),
    val floodBearing: Double? = null,
    val ebbBearing: Double? = null,
    val hasCrossAxis: Boolean = false,
) {
    internal fun clipped(window: TimelineWindow): TimelineChunkData {
        fun owns(time: Instant) = time >= window.start && time < window.end
        fun <T> bracket(points: List<T>, time: (T) -> Instant): List<T> {
            val first = points.indexOfLast { time(it) <= window.start }.coerceAtLeast(0)
            val after = points.indexOfFirst { time(it) >= window.end }
            return points.subList(first, if (after < 0) points.size else after + 1).toList()
        }
        return copy(tidePoints = bracket(tidePoints) { it.time }, tideRates = bracket(tideRates) { it.time },
            tideEvents = tideEvents.filter { owns(it.time) }, currentPoints = bracket(currentPoints) { it.time },
            currentEvents = currentEvents.filter { owns(it.time) }, derivedSlacks = derivedSlacks.filter { owns(it.time) })
    }
}

data class TimelinePublication(val window: TimelineWindow, val data: TimelineChunkData, val today: LocalDate)

private fun <T> List<T>.dedupeEvents(time: (T) -> Instant, kind: (T) -> Any): List<T> = buildList {
    for (event in this@dedupeEvents) {
        val previous = lastOrNull()
        if (previous == null || kind(previous) != kind(event) ||
            Duration.between(time(previous), time(event)) >= Duration.ofSeconds(2)) add(event)
    }
}

/** The loader samples on the supplied worker, with enough context for roots on either edge.
 * Return day chrome for the local day before the span through its end day, inclusive.
 * Throw on missing coverage so the published strip never crosses a hole.
 */
class TimelineChunks(
    private val origin: LocalDate,
    private val zone: ZoneId,
    now: Instant,
    worker: Executor = ForkJoinPool.commonPool(),
    private val load: suspend (TimelineWindow) -> TimelineChunkData,
) : AutoCloseable {
    private class Request(val range: LongRange, var explicit: Boolean) {
        val result = CompletableFuture<TimelinePublication?>()
        var work: CompletableFuture<TimelinePublication?>? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())
    private var today = now.atZone(zone).toLocalDate()
    private var closed = false
    private val cache = mutableMapOf<Long, TimelineChunkData>()
    private var visible: LongRange? = null
    private var pending: Request? = null

    @Volatile var published: TimelinePublication? = null
        private set

    private fun index(time: Instant) = Math.floorDiv(ChronoUnit.DAYS.between(origin, time.atZone(zone).toLocalDate()), 7L)
    private fun boundary(i: Long) = origin.plusWeeks(i).atStartOfDay(zone).toInstant()

    fun open(target: Instant) = cover(target, Duration.ofHours(48))
    fun jump(target: Instant, viewport: Double): CompletableFuture<TimelinePublication?> {
        require(viewport.isFinite() && viewport >= 0.0)
        if (viewport == 0.0) return open(target)
        return cover(target, Duration.ofNanos((viewport * TimelineWindow.SECONDS_PER_DP * 5e8).roundToLong()))
    }

    private fun cover(target: Instant, radius: Duration): CompletableFuture<TimelinePublication?> =
        request(index(target.minus(radius))..index(target.plus(radius)), true)

    @Synchronized fun update(
        target: Instant,
        quiet: Boolean,
        displayed: TimelineWindow? = null,
    ): CompletableFuture<TimelinePublication?> {
        check(!closed)
        val center = index(target)
        val old = displayed?.let { index(it.start)..index(it.end.minusNanos(1)) } ?: visible
        pending?.let { if (it.explicit) return it.result }
        if (!quiet && old != null && center !in old) return CompletableFuture.completedFuture(published)
        val first = if (old == null) center - 2 else if (quiet)
            minOf(center - 2, old.first.coerceAtLeast(center - 3)) else old.first
        val last = if (old == null) center + 2 else maxOf(center + 2, old.last.coerceAtMost(center + 3))
        val end = if (quiet || old == null) last else maxOf(last, old.last)
        return request(first..end, false)
    }

    @Synchronized fun setNow(now: Instant) {
        val date = now.atZone(zone).toLocalDate()
        if (today == date) return
        today = date
        published = published?.copy(today = date)
    }

    @Synchronized fun cancelPending(explicitOnly: Boolean = false) {
        val active = pending ?: return
        if (explicitOnly && !active.explicit) return
        pending = null
        active.work?.cancel(false)
        active.result.complete(null)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        published = null
        visible = null
        cache.clear()
        cancelPending()
        scope.cancel()
    }

    @Synchronized private fun request(range: LongRange, explicit: Boolean): CompletableFuture<TimelinePublication?> {
        check(!closed)
        pending?.let {
            if (it.range == range) {
                if (explicit) it.explicit = true
                return it.result
            }
        }
        cancelPending()
        if (range == visible || explicit && visible?.let { range.first >= it.first && range.last <= it.last } == true)
            return CompletableFuture.completedFuture(published)
        val retained = visible?.let { it.first - 1..it.last + 1 }
        cache.keys.retainAll { it in range || retained != null && it in retained }
        val ready = cache.filterKeys { it in range }.toMutableMap()
        val active = Request(range, explicit)
        pending = active
        val work = scope.future<TimelinePublication?> {
            for (i in range) {
                ensureActive()
                if (i !in ready) {
                    val window = TimelineWindow(boundary(i), boundary(i + 1))
                    val data = load(window).clipped(window)
                    synchronized(this@TimelineChunks) {
                        if (pending !== active) return@future null
                        cache[i] = data
                    }
                    ready[i] = data
                }
            }
            val chunks = range.map { ready.getValue(it) }
            // Keep bracketing samples at the outer edges; the chunk on the right owns each seam.
            fun <T> samples(points: (TimelineChunkData) -> List<T>, time: (T) -> Instant): List<T> = buildList {
                for (i in range) {
                    val start = boundary(i)
                    val end = boundary(i + 1)
                    for (point in points(ready.getValue(i))) {
                        if ((i == range.first || time(point) >= start) && (i == range.last || time(point) < end))
                            add(point)
                    }
                }
            }
            val data = TimelineChunkData(
                samples({ it.tidePoints }, { it.time }), samples({ it.tideRates }, { it.time }),
                chunks.flatMap { it.tideEvents }.dedupeEvents({ it.time }, { it.kind }),
                samples({ it.currentPoints }, { it.time }),
                chunks.flatMap { it.currentEvents }.dedupeEvents({ it.time }, { it.kind }),
                chunks.flatMap { it.derivedSlacks }.dedupeEvents({ it.time }, { it.referenceTurn }),
                chunks.flatMap { it.days }.distinctBy { it.date }.sortedBy { it.date },
                chunks.first().floodBearing, chunks.first().ebbBearing, chunks.first().hasCrossAxis,
            )
            synchronized(this@TimelineChunks) {
                ensureActive()
                if (pending !== active) return@future null
                cache.keys.retainAll { it in range.first - 1..range.last + 1 }
                visible = range
                TimelinePublication(TimelineWindow(boundary(range.first), boundary(range.last + 1)), data, today)
                    .also { published = it }
            }
        }
        active.work = work
        work.whenComplete { result, failure ->
            val current = synchronized(this) {
                (pending === active).also { if (it) pending = null }
            }
            if (!current) active.result.complete(null)
            else if (failure != null) active.result.completeExceptionally(failure)
            else active.result.complete(result)
        }
        active.result.whenComplete { _, _ -> if (active.result.isCancelled) work.cancel(false) }
        return active.result
    }
}
