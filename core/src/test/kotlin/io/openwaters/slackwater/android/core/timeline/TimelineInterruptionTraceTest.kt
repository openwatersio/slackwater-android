package io.openwaters.slackwater.android.core.timeline

import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class TimelineInterruptionTraceTest {
    @Test fun aDraggedStripKeepsItsDataUntilTheNewestRequestedWeekIsReady() {
        val anchor = java.time.LocalDate.parse("2026-03-01")
        val now = Instant.parse("2026-03-01T12:00:00Z")
        val jobs = ArrayDeque<Runnable>()
        val store = TimelineChunks(anchor, ZoneOffset.UTC, now, Executor(jobs::addLast)) {
            TimelineChunkData(days = listOf(TimelineDay(it.start.atZone(ZoneOffset.UTC).toLocalDate())))
        }
        val initial = store.open(now)
        jobs.removeFirst().run()
        val opening = requireNotNull(initial.join())
        var elapsed = 0L
        val controller = TimelineController(now, ZoneOffset.UTC, opening.window, 360.0, initialSelection = now,
            clockMillis = { elapsed })
        val timeline = ContinuousTimeline(controller, store, opening, Executor { it.run() })
        data class Trace(val selected: Instant, val anchor: java.time.LocalDate, val motion: TimelineMotion, val pending: Long?)
        val trace = mutableListOf<Trace>()
        fun record(state: TimelineState) {
            trace += Trace(state.selected, state.anchor, state.motion, state.pendingGeneration)
        }
        fun send(action: TimelineAction): TimelineState {
            elapsed++
            timeline.dispatch(action)
            return timeline.state.also(::record)
        }

        send(TimelineAction.BeginDrag)
        val winter = Instant.parse("2026-12-22T12:00:00Z")
        val summer = Instant.parse("2026-07-22T12:00:00Z")
        val first = send(TimelineAction.Request(winter)).generation
        val last = send(TimelineAction.Request(summer)).generation
        jobs.removeFirst().run()
        assertEquals(opening, timeline.content)
        jobs.removeFirst().run()
        val published = requireNotNull(store.published)
        assertTrue(summer in published.window.start..published.window.end)
        record(timeline.state)
        assertEquals(opening, timeline.content)
        assertEquals(opening.window, controller.state.coverage)
        val landed = send(TimelineAction.EndDrag(momentum = true))
        assertEquals(published.window, landed.coverage)
        assertEquals(published, timeline.content)
        assertEquals(listOf(
            Trace(now, anchor, TimelineMotion.DRAG, null),
            Trace(now, anchor, TimelineMotion.DRAG, first),
            Trace(now, anchor, TimelineMotion.DRAG, last),
            Trace(now, anchor, TimelineMotion.DRAG, last),
            Trace(summer, anchor, TimelineMotion.RESTING, null),
        ), trace)
        assertEquals(summer, send(TimelineAction.MomentumBy(first, 140.0)).selected)
        elapsed += 600
        assertEquals(summer.atZone(ZoneOffset.UTC).toLocalDate(), send(TimelineAction.Tick).anchor)
        timeline.dispatch(TimelineAction.Foreground(now.plusSeconds(86400)))
        assertEquals(anchor.plusDays(1), timeline.content.today)
        assertEquals(summer, timeline.state.selected)
        timeline.close()
    }

    @Test fun wideningBeforeDeliveryOrFingerUpStillLandsExactlyAtTheRequestedTime() {
        for (loadBeforeResize in listOf(false, true)) {
            val zone = ZoneOffset.UTC
            val now = Instant.parse("2026-03-01T12:00:00Z")
            val target = Instant.parse("2026-07-19T12:00:00Z")
            val jobs = ArrayDeque<Runnable>()
            val store = TimelineChunks(now.atZone(zone).toLocalDate(), zone, now, Executor(jobs::addLast)) {
                TimelineChunkData()
            }
            val initial = store.open(now)
            jobs.removeFirst().run()
            val opening = requireNotNull(initial.join())
            val controller = TimelineController(now, zone, opening.window, 360.0, initialSelection = now)
            ContinuousTimeline(controller, store, opening, Executor { it.run() }).use { timeline ->
                timeline.dispatch(TimelineAction.BeginDrag)
                timeline.dispatch(TimelineAction.Request(target))
                if (loadBeforeResize) jobs.removeFirst().run()
                timeline.dispatch(TimelineAction.Viewport(900.0))
                timeline.dispatch(TimelineAction.EndDrag(momentum = true))
                while (jobs.isNotEmpty()) jobs.removeFirst().run()
                assertEquals(target, timeline.state.selected)
                assertEquals(target, timeline.content.window.clamp(target, 900.0))
            }
        }
    }

    @Test fun aRightSidePrefetchSurvivesTheEndOfMomentum() {
        val now = Instant.parse("2026-03-01T12:00:00Z")
        val jobs = ArrayDeque<Runnable>()
        val zone = ZoneOffset.UTC
        val store = TimelineChunks(now.atZone(zone).toLocalDate(), zone, now, Executor(jobs::addLast)) {
            TimelineChunkData()
        }
        val initial = store.open(now)
        jobs.removeFirst().run()
        val opening = requireNotNull(initial.join())
        val controller = TimelineController(now, zone, opening.window, 360.0, initialSelection = now)
        ContinuousTimeline(controller, store, opening, Executor { it.run() }).use { timeline ->
            timeline.dispatch(TimelineAction.BeginDrag)
            timeline.dispatch(TimelineAction.EndDrag(momentum = true))
            val generation = timeline.state.generation
            timeline.dispatch(TimelineAction.MomentumBy(generation - 1, -30.0))
            assertEquals(now, timeline.state.selected)
            timeline.dispatch(TimelineAction.MomentumBy(generation, -30.0))
            val extending = timeline.refresh()
            timeline.dispatch(TimelineAction.EndMomentum(generation))
            while (jobs.isNotEmpty()) jobs.removeFirst().run()
            val result = extending.join()
            assertTrue(result.coverage.end > opening.window.end)
            assertEquals(opening.window.start, result.coverage.start)
            assertEquals(timeline.content.window, result.coverage)
        }
    }
}
