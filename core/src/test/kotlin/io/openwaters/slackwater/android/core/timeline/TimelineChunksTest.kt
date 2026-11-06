package io.openwaters.slackwater.android.core.timeline

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletionException
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class TimelineChunksTest {
    private val zone = ZoneId.of("America/Vancouver")
    private val origin = LocalDate.parse("2026-03-06")
    private fun start(date: LocalDate) = date.atStartOfDay(zone).toInstant()
    private fun data(window: TimelineWindow): TimelineChunkData {
        val date = window.start.atZone(zone).toLocalDate()
        return TimelineChunkData(
            currentPoints = listOf(window.start, window.end).map { CurrentPoint(it, 1.0) },
            currentEvents = listOf(window.start, window.end.minusSeconds(1)).map {
                CurrentEvent(it, 0.0, CurrentEventKind.SLACK)
            },
            days = (-1L..7L).map { TimelineDay(date.plusDays(it)) },
        )
    }

    @Test fun sevenCivilDaysOwnTheirSamplesEventsAndChromeAcrossDst() {
        for ((date, hours) in listOf(origin to 167L, LocalDate.parse("2025-10-30") to 169L)) {
            val spans = mutableListOf<TimelineWindow>()
            val store = TimelineChunks(date, zone, start(date), Executor { it.run() }) {
                spans.add(it)
                data(it)
            }
            val snapshot = store.open(start(date)).join()!!
            assertEquals(listOf(start(date.minusWeeks(1)), start(date)), spans.map { it.start })
            assertEquals(hours, Duration.between(spans.last().start, spans.last().end).toHours())
            assertEquals(spans.map { it.start } + spans.last().end, snapshot.data.currentPoints.map { it.time })
            assertEquals(3, snapshot.data.currentEvents.size)
            assertEquals(16, snapshot.data.days.size)
            store.setNow(start(date.plusDays(1)))
            assertEquals(date.plusDays(1), store.published!!.today)
        }
    }

    @Test fun movingOnlyExtendsRightAndSettlingBoundsTheCache() {
        val loads = mutableListOf<Instant>()
        val store = TimelineChunks(origin, zone, start(origin), Executor { it.run() }) {
            loads += it.start
            data(it)
        }
        val initial = store.update(start(origin), true).join()!!.window
        for (i in 0L..12) {
            val motion = store.update(start(origin.plusWeeks(i)), false).join()!!.window
            assertEquals(initial.start, motion.start)
            assertTrue(motion.end >= start(origin.plusWeeks(i + 2)))
        }
        val rest = store.update(start(origin.plusWeeks(12)), true).join()!!.window
        assertEquals(start(origin.plusWeeks(9)), rest.start)
        assertTrue(rest.end <= start(origin.plusWeeks(16)))
        val count = loads.size
        assertEquals(rest, store.update(start(origin.plusWeeks(12)), true).join()!!.window)
        assertEquals(count, loads.size)
        store.jump(start(origin), 390.0).join()
        assertTrue(loads.size > count)
    }

    @Test fun supersededWorkAndMissingChunksCannotPublish() {
        val tasks = ArrayDeque<Runnable>()
        var missing = true
        val store = TimelineChunks(origin, zone, start(origin), Executor { tasks.add(it) }) {
            if (missing && it.start == start(origin)) error("unavailable")
            data(it)
        }
        val old = store.open(start(origin.minusWeeks(9)))
        val jump = store.jump(start(origin), 390.0)
        tasks.removeFirst().run()
        assertNull(old.join())
        tasks.removeFirst().run()
        assertThrows(CompletionException::class.java) { jump.join() }
        assertNull(store.published)
        missing = false
        val retry = store.jump(start(origin), 390.0)
        tasks.removeFirst().run()
        val span = retry.join()!!.window
        assertEquals(start(origin), span.clamp(start(origin), 390.0))
    }

    @Test fun harmonicRepositoryLoadsPaddedRootsAndRealDayChromeWithoutDuplicateSeams() {
        val station = StationIdentity(StationId("test"), "Vancouver", "BC", 49.28, -123.12, StationKind.Tide)
        val engine = Station(listOf(HarmonicConstituent("M2", 1.0, 30.0)), 0.0)
        val spans = mutableListOf<TimeSpan>()
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
                spans += span
                return PredictionResult.Ready(id, span, "Test", StationCapabilities.HARMONIC_TIDE,
                    EngineAdapter.tide(engine, span))
            }
        }
        val store = TimelineChunks(origin, zone, start(origin)) { repository.timelineChunk(station, zone, it) }
        val snapshot = store.open(start(origin)).join()!!
        assertEquals(snapshot.window.start.minus(Duration.ofHours(6)), spans.first().start)
        val data = snapshot.data
        assertTrue(data.tidePoints.isNotEmpty())
        assertTrue(data.tidePoints.zipWithNext().all { (a, b) -> b.time == a.time.plusSeconds(600) })
        assertEquals(snapshot.window.end, data.tidePoints.last().time)
        assertEquals(1, data.tidePoints.count { it.time == start(origin) })
        assertTrue(data.tideEvents.size > 40)
        assertTrue(data.tideEvents.zipWithNext().all { (a, b) -> a.time < b.time && a.kind != b.kind })
        assertEquals(16, data.days.size)
        for (day in data.days) {
            assertEquals(day.date, day.sunrise!!.atZone(zone).toLocalDate())
            assertEquals(day.date, day.sunset!!.atZone(zone).toLocalDate())
            assertTrue(day.sunrise < day.sunset)
        }
    }

    @Test fun repositoryCannotPublishABoundedOrInterruptedSeriesAsContinuous() {
        val station = StationIdentity(StationId("test"), "Vancouver", "BC", 49.28, -123.12, StationKind.Current)
        var capability = StationCapabilities.ONLINE_CURRENT
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose) =
                PredictionResult.Ready(id, span, "Test", capability, PredictionSeries.Current(
                    listOf(CurrentPoint(span.start, 0.0), CurrentPoint(span.end, 1.0)), emptyList(), null, null))
        }
        val store = TimelineChunks(origin, zone, start(origin)) { repository.timelineChunk(station, zone, it) }
        for (model in listOf(StationCapabilities.ONLINE_CURRENT, StationCapabilities.HARMONIC_CURRENT)) {
            capability = model
            assertThrows(CompletionException::class.java) { store.open(start(origin)).join() }
            assertNull(store.published)
        }
    }

    @Test fun busyUpdateCannotFillADisjointGapOrSupersedeExplicitCoverage() {
        val tasks = ArrayDeque<Runnable>()
        val store = TimelineChunks(origin, zone, start(origin), Executor { tasks.add(it) }, ::data)
        val opening = store.open(start(origin))
        tasks.removeFirst().run()
        assertEquals(opening.join(), store.update(start(origin.plusYears(40)), false).join())
        assertTrue(tasks.isEmpty())
        val jump = store.jump(start(origin.plusYears(40)), 390.0)
        assertSame(jump, store.update(start(origin), true))
        tasks.removeFirst().run()
        assertNotNull(jump.join())
    }

    @Test fun unsupportedSkyDoesNotDropTheCivilDayOrItsWater() {
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
                val points = generateSequence(span.start) { it.plusSeconds(600) }.takeWhile { it <= span.end }
                    .map { CurrentPoint(it, 0.5) }.toList()
                return PredictionResult.Ready(id, span, "Test", StationCapabilities.HARMONIC_CURRENT,
                    PredictionSeries.Current(points, emptyList(), 90.0, 270.0))
            }
        }
        for ((day, latitude) in listOf(origin to Double.NaN, origin.minusYears(100) to 49.28)) {
            val station = StationIdentity(StationId("test"), "Vancouver", "BC", latitude, -123.12, StationKind.Current)
            val store = TimelineChunks(day, zone, start(day)) { repository.timelineChunk(station, zone, it) }
            val result = store.open(start(day)).join()!!.data
            assertEquals(16, result.days.size)
            assertTrue(result.days.all { it.sunrise == null && it.moonrise == null })
            assertTrue(result.currentPoints.isNotEmpty())
        }
    }

    @Test fun cancelledCoroutineLoadCanRetryTheSameRequest() {
        var cancel = true
        val store = TimelineChunks(origin, zone, start(origin)) {
            if (cancel) throw java.util.concurrent.CancellationException()
            data(it)
        }
        assertThrows(java.util.concurrent.CancellationException::class.java) { store.open(start(origin)).join() }
        cancel = false
        assertNotNull(store.open(start(origin)).join())
    }

    @Test fun supersedingASuspendedLoadCancelsItAndKeepsTheFinishedWeek() {
        var cancelled = false
        val loaded = mutableListOf<Instant>()
        val store = TimelineChunks(origin, zone, start(origin), Executor { it.run() }) {
            loaded += it.start
            if (it.start == start(origin)) try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
            data(it)
        }
        val opening = store.open(start(origin))
        assertSame(opening, store.open(start(origin)))
        val nearby = store.jump(start(origin.minusDays(3)), 390.0)
        assertNull(opening.join())
        assertTrue(cancelled)
        assertEquals(start(origin.minusWeeks(1)), nearby.join()!!.window.start)
        assertEquals(listOf(start(origin.minusWeeks(1)), start(origin)), loaded)
    }

    @Test fun closingTheStationCancelsSuspendedWorkAndReleasesItsPublication() {
        var suspendLoad = false
        var cancelled = false
        val store = TimelineChunks(origin, zone, start(origin), Executor { it.run() }) {
            if (suspendLoad) try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
            data(it)
        }
        store.open(start(origin)).join()
        suspendLoad = true
        val nearby = store.update(start(origin), true)
        store.cancelPending(explicitOnly = true)
        assertFalse(nearby.isDone)
        store.close()
        store.close()
        assertTrue(cancelled)
        assertNull(nearby.join())
        assertNull(store.published)
        assertThrows(IllegalStateException::class.java) { store.open(start(origin)) }
    }

    @Test fun busyRefreshUsesTheOriginStillDisplayedAfterAQuietLoad() {
        val store = TimelineChunks(origin, zone, start(origin), Executor { it.run() }, ::data)
        val displayed = store.open(start(origin)).join()!!.window
        val refused = store.update(start(origin), true).join()!!.window
        assertTrue(refused.start < displayed.start)
        val moving = store.update(start(origin), false, displayed).join()!!.window
        assertEquals(displayed.start, moving.start)
        assertEquals(refused.end, moving.end)
    }

    @Test fun unmeasuredJumpKeepsRoomForTheIncomingViewport() {
        val store = TimelineChunks(origin, zone, start(origin), Executor { it.run() }, ::data)
        val target = start(origin).minusSeconds(60)
        val snapshot = store.jump(target, 0.0).join()!!
        assertEquals(target, snapshot.window.clamp(target, 390.0))
    }

    @Test fun productionSamplerRunsOffCallerThread() {
        val caller = Thread.currentThread()
        val chunks = TimelineChunks(origin, zone, start(origin)) {
            assertNotSame(caller, Thread.currentThread())
            data(it)
        }
        assertNotNull(chunks.open(start(origin)).join())
    }
}
