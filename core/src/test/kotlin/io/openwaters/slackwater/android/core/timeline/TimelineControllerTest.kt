package io.openwaters.slackwater.android.core.timeline

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TimelineControllerTest {
    private val now = Instant.parse("2026-03-08T07:59:00Z") // One minute before Vancouver midnight.
    private val zone = ZoneId.of("America/Vancouver")
    private val today = now.atZone(zone).toLocalDate()
    private val full = TimelineWindow(now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(30)))
    private fun controller(
        initial: Instant = now,
        mode: TimelineMode = TimelineMode.CONTINUOUS,
        window: TimelineWindow = full,
        reduceMotion: Boolean = false,
    ) = TimelineController(now, zone, window, 360.0, mode, initial, reduceMotion, clockMillis = { 0L })

    @Test fun openingFramesDriveSelectionAndQuietTimersWithoutAdvancingSessionNow() {
        val c = controller(now.minusSeconds(7200))
        assertEquals(TimelineMotion.OPENING, c.state.motion)
        assertEquals(now.minusSeconds(3600), c.dispatch(TimelineAction.Tick, 325).selected)
        assertEquals(now, c.dispatch(TimelineAction.Tick, 650).selected)
        assertFalse(c.dispatch(TimelineAction.Tick, 1099).pillsVisible)
        assertTrue(c.dispatch(TimelineAction.Tick, 1100).pillsVisible)
        assertFalse(c.dispatch(TimelineAction.Tick, 1249).settled)
        assertTrue(c.dispatch(TimelineAction.Tick, 1250).settled)
        assertEquals(now, c.state.now)
        assertEquals(now, controller(now.minusSeconds(7200), reduceMotion = true).state.selected)
        val linked = now.plus(Duration.ofDays(14))
        assertEquals(linked.atZone(zone).toLocalDate(), controller(linked).state.anchor)
        assertEquals(TimelineMotion.RESTING, controller(linked).state.motion)
    }

    @Test fun hingeFramesUseTheRestoredMomentAndUserInputOwnsTheNextGeneration() {
        val c = controller()
        val gen = c.dispatch(TimelineAction.BeginHingeFollow).generation
        assertEquals(now.plusSeconds(21600), c.state.selected)
        assertEquals(now.plusSeconds(10800), c.dispatch(TimelineAction.AnimationFrame(gen, .5)).selected)
        val grabbed = c.dispatch(TimelineAction.BeginDrag).selected
        c.dispatch(TimelineAction.AnimationFrame(gen, 1.0))
        assertEquals(grabbed, c.state.selected)
        assertEquals(TimelineMotion.DRAG, c.state.motion)
        val reduced = controller(reduceMotion = true)
        assertEquals(now, reduced.dispatch(TimelineAction.BeginHingeFollow).selected)
        assertEquals(TimelineMotion.RESTING, reduced.state.motion)
    }

    @Test fun restoredSelectionAtOpeningSentinelRemainsParkedAcrossPostureAndModelChanges() {
        val selected = now.minusSeconds(7200)
        val anchor = today.minusDays(3)
        val restored = TimelineController(now, zone, full, 0.0, initialSelection = selected,
            clockMillis = { 0L }, anchor = anchor, allowOpening = false)
        for (width in listOf(360.0, 800.0, 320.0)) {
            restored.dispatch(TimelineAction.Viewport(width))
            val state = restored.dispatch(TimelineAction.Tick, 700)
            assertEquals(selected, state.selected)
            assertEquals(selected, state.coverage.selected(state.offset, width))
            assertEquals(anchor, state.anchor)
            assertEquals(TimelineMotion.RESTING, state.motion)
        }
    }

    @Test fun initialLayoutStartsOpeningOnceAndANewerRequestWinsBeforeLayout() {
        fun unmeasured(reduceMotion: Boolean = false) =
            TimelineController(now, zone, full, 0.0, reduceMotion = reduceMotion, clockMillis = { 0L })
        val c = unmeasured()
        assertEquals(TimelineMotion.INITIAL_POSITIONING, c.dispatch(TimelineAction.Tick, 2000).motion)
        c.dispatch(TimelineAction.Viewport(360.0), 2000)
        assertEquals(now.minusSeconds(3600), c.dispatch(TimelineAction.Tick, 2325).selected)
        c.dispatch(TimelineAction.Viewport(450.0), 2325)
        assertEquals(now, c.dispatch(TimelineAction.Tick, 2650).selected)
        val direct = unmeasured(true)
        direct.dispatch(TimelineAction.SelectionFeedback(now.minusSeconds(7200)))
        direct.dispatch(TimelineAction.Viewport(360.0))
        assertEquals(now, direct.state.selected)
        val replaced = unmeasured(true)
        replaced.dispatch(TimelineAction.Request(now.plusSeconds(3600)))
        replaced.dispatch(TimelineAction.SelectionFeedback(now.minusSeconds(7200)))
        replaced.dispatch(TimelineAction.Viewport(360.0))
        assertEquals(now.plusSeconds(3600), replaced.state.selected)
    }

    @Test fun interruptionTraceFromOpeningThroughDragQueuedRidesAndStaleFrames() {
        val c = controller(now.minusSeconds(7200))
        val trace = mutableListOf<String>()
        fun record(action: TimelineAction, ms: Long) {
            val s = c.dispatch(action, ms)
            trace += "${Duration.between(now, s.selected).seconds} ${s.anchor} ${s.motion} ${s.pendingGeneration}"
        }
        record(TimelineAction.Tick, 325)
        record(TimelineAction.BeginDrag, 326)
        record(TimelineAction.DragBy(-9.0), 327)
        record(TimelineAction.Request(now.plusSeconds(3600)), 328)
        record(TimelineAction.Request(now.plusSeconds(7200)), 329)
        record(TimelineAction.EndDrag(momentum = true), 330)
        val ride = c.state.generation
        record(TimelineAction.AnimationFrame(ride, 0.5), 390)
        record(TimelineAction.SelectionFeedback(now), 391)
        record(TimelineAction.BeginDrag, 392)
        record(TimelineAction.AnimationFrame(ride, 1.0), 393)
        assertEquals(listOf(
            "-3600 $today OPENING null", "-3600 $today DRAG null", "-1800 $today DRAG null",
            "-1800 $today DRAG 2", "-1800 $today DRAG 3", "-1800 $today RIDE null",
            "2700 $today RIDE null", "2700 $today RIDE null", "2700 $today DRAG null", "2700 $today DRAG null",
        ), trace)
    }

    @Test fun tappingTimeOrOpeningDatePickerInterruptsOpeningAtRecognition() {
        val c = controller(now.minusSeconds(7200))
        val grabbed = c.dispatch(TimelineAction.Tick, 325).selected
        c.dispatch(TimelineAction.Stop, 326)
        assertEquals(grabbed, c.dispatch(TimelineAction.Tick, 650).selected)
        val tapped = controller(now.minusSeconds(7200))
        tapped.dispatch(TimelineAction.Tick, 325)
        val ride = tapped.dispatch(TimelineAction.Tap(now.plusSeconds(3600)), 326)
        assertEquals(TimelineMotion.RIDE, ride.motion)
        tapped.dispatch(TimelineAction.Tick, 650)
        assertEquals(grabbed, tapped.state.selected)
        tapped.dispatch(TimelineAction.AnimationFrame(ride.generation, 1.0), 800)
        assertEquals(now.plusSeconds(3600), tapped.state.selected)
    }

    @Test fun foregroundUsesOldHomeThresholdAndRestoresLocalScheduleOnlyAtHome() {
        val refresh = now.plus(Duration.ofDays(2))
        val home = controller(now.plusSeconds(200), reduceMotion = true)
        home.dispatch(TimelineAction.Foreground(refresh))
        assertEquals(refresh, home.state.selected)
        assertEquals(refresh.atZone(zone).toLocalDate(), home.state.anchor)
        val away = controller(now.plusSeconds(200).plusNanos(1))
        away.dispatch(TimelineAction.Foreground(refresh))
        assertEquals(now.plusSeconds(200).plusNanos(1), away.state.selected)
        assertEquals(refresh, away.state.now)
        assertEquals(today, away.state.anchor)
    }

    @Test fun freeScrollSnapsOnlyAtRestAndTapInterruptsMagneticAndMomentumFrames() {
        val c = controller()
        val stop = now.plusSeconds(3600)
        c.dispatch(TimelineAction.Targets(MagneticTargets(listOf(stop))))
        c.dispatch(TimelineAction.BeginDrag)
        c.dispatch(TimelineAction.DragBy(-15.0))
        val fling = c.dispatch(TimelineAction.EndDrag(true)).generation
        c.dispatch(TimelineAction.MomentumBy(fling, -1.0))
        assertEquals(now.plusSeconds(3200), c.state.selected)
        val magnet = c.dispatch(TimelineAction.EndMomentum(fling))
        assertEquals(TimelineMotion.MAGNETIC_SETTLE, magnet.motion)
        assertEquals(stop, magnet.move?.to)
        c.dispatch(TimelineAction.Tap(now, press = true))
        c.dispatch(TimelineAction.MomentumBy(fling, -1.0))
        c.dispatch(TimelineAction.AnimationFrame(magnet.generation, 1.0))
        assertEquals(now, c.state.selected)
    }

    @Test fun asyncCoverageCompletesBeforeOrAfterLiftAndNewInteractionInvalidatesOldLoads() {
        for (beforeLift in listOf(true, false)) {
            val c = controller()
            val target = now.plus(Duration.ofDays(60))
            val replacement = TimelineWindow(target.minusSeconds(100000), target.plusSeconds(100000))
            c.dispatch(TimelineAction.BeginDrag)
            val generation = c.dispatch(TimelineAction.PrepareRequest(target)).generation
            val loaded = TimelineAction.RequestCoverage(generation, replacement)
            if (beforeLift) c.dispatch(loaded)
            assertEquals(full, c.state.coverage)
            c.dispatch(TimelineAction.EndDrag(true))
            if (!beforeLift) {
                assertEquals(TimelineMotion.RESTING, c.state.motion)
                assertTrue(c.state.awaitingCoverage)
                c.dispatch(loaded)
            }
            assertEquals(replacement, c.state.coverage)
            assertEquals(target, c.state.selected)
            assertNull(c.state.pendingGeneration)
            assertFalse(c.state.awaitingCoverage)
            val cancelled = c.dispatch(TimelineAction.PrepareRequest(now)).generation
            c.dispatch(TimelineAction.BeginDrag)
            c.dispatch(TimelineAction.RequestCoverage(cancelled, full))
            assertEquals(replacement, c.state.coverage)
        }
    }

    @Test fun pickerUsesCivilNoonAndOnlyContinuousScheduleReanchorsOutsideLookbackAfter600ms() {
        val c = controller(reduceMotion = true)
        val day = today.plusDays(14)
        c.dispatch(TimelineAction.PickDay(day))
        assertEquals(day.atTime(12, 0).atZone(zone).toInstant(), c.state.selected)
        val lookback = day.minusDays(1).atStartOfDay(zone).toInstant()
        c.dispatch(TimelineAction.Request(lookback))
        assertEquals(day, c.dispatch(TimelineAction.Tick, 600).anchor)
        c.dispatch(TimelineAction.Request(now), 700)
        assertEquals(day, c.dispatch(TimelineAction.Tick, 1299).anchor)
        assertEquals(today, c.dispatch(TimelineAction.Tick, 1300).anchor)
        val preview = controller(mode = TimelineMode.PREVIEW, reduceMotion = true)
        preview.dispatch(TimelineAction.PickDay(day))
        assertEquals(now, preview.state.selected)
        preview.dispatch(TimelineAction.Request(lookback))
        assertEquals(today, preview.dispatch(TimelineAction.Tick, 600).anchor)
    }

    @Test fun continuousExplicitRequestsRequireHalfViewportOnBothSidesAndTapsClamp() {
        val c = controller(window = full)
        val target = full.end.minusSeconds(1)
        val first = c.dispatch(TimelineAction.Request(target))
        assertTrue(first.awaitingCoverage)
        assertEquals(target, c.pendingTarget)
        assertEquals(now, c.state.selected)
        val second = c.dispatch(TimelineAction.Now(target.plusSeconds(1)))
        assertTrue(second.awaitingCoverage)
        assertEquals(full.end, c.pendingTarget)
        val day = today.plusWeeks(8)
        c.dispatch(TimelineAction.PickDay(day))
        assertEquals(day.atTime(12, 0).atZone(zone).toInstant(), c.pendingTarget)
        c.dispatch(TimelineAction.RequestCoverage(first.generation, full))
        assertTrue(c.state.awaitingCoverage)
        c.dispatch(TimelineAction.Tap(target, press = true))
        assertNull(c.pendingTarget)
        assertEquals(full.clamp(target, 360.0), c.state.selected)
        val foreground = controller()
        foreground.dispatch(TimelineAction.Foreground(full.end))
        assertEquals(full.end, foreground.pendingTarget)
        foreground.dispatch(TimelineAction.Request(full.end, direct = true, coverage = full))
        assertNull(foreground.pendingTarget)
        assertEquals(full.clamp(full.end, 360.0), foreground.state.selected)
    }

    @Test fun resizeRevalidatesInflightAndStagedContinuousCoveragePreservingRequestAndFinger() {
        val target = now.plus(Duration.ofDays(40))
        val anchor = target.atZone(zone).toLocalDate()
        val narrow = TimelineWindow(target.minusSeconds(43200), target.plus(Duration.ofDays(6)))
        val wide = TimelineWindow(narrow.start.minus(Duration.ofDays(7)), narrow.end)
        for (alreadyStaged in listOf(false, true)) {
            val c = controller()
            c.dispatch(TimelineAction.BeginDrag)
            val old = c.dispatch(TimelineAction.Request(target, direct = true, anchor = anchor)).generation
            if (alreadyStaged) c.dispatch(TimelineAction.RequestCoverage(old, narrow))
            val resized = c.dispatch(TimelineAction.Viewport(900.0))
            assertTrue(resized.awaitingCoverage)
            assertTrue(resized.generation > old)
            assertEquals(target, c.pendingTarget)
            assertEquals(TimelineMotion.DRAG, resized.motion)
            c.dispatch(TimelineAction.RequestCoverage(old, narrow))
            assertTrue(c.state.awaitingCoverage)
            c.dispatch(TimelineAction.RequestCoverage(resized.generation, narrow))
            assertTrue(c.state.awaitingCoverage)
            assertTrue(c.state.generation > resized.generation)
            c.dispatch(TimelineAction.RequestCoverage(c.state.generation, wide))
            assertEquals(now, c.state.selected)
            c.dispatch(TimelineAction.EndDrag(true))
            assertEquals(target, c.state.selected)
            assertEquals(anchor, c.state.anchor)
            assertEquals(TimelineMotion.RESTING, c.state.motion)
        }
    }

    @Test fun farRequestBeforeLayoutWaitsForMeasuredCoverageWhileOnlineResizeKeepsItsFetch() {
        val c = TimelineController(now, zone, full, 0.0, clockMillis = { 0 })
        val target = full.end
        val first = c.dispatch(TimelineAction.Request(target))
        assertTrue(first.awaitingCoverage)
        val measured = c.dispatch(TimelineAction.Viewport(900.0))
        assertTrue(measured.awaitingCoverage)
        assertTrue(measured.generation > first.generation)
        assertEquals(target, c.pendingTarget)
        val online = controller(mode = TimelineMode.ONLINE, window = TimelineWindow.fixed(today, zone))
        val pending = online.dispatch(TimelineAction.PickDay(today.plusWeeks(1)))
        assertEquals(pending.generation, online.dispatch(TimelineAction.Viewport(900.0)).generation)
        assertTrue(online.state.awaitingCoverage)
    }

    @Test fun onlinePickerKeepsOriginalWeekUntilMatchingBlockAndNowSupersedesPendingWeek() {
        val window = TimelineWindow.fixed(today, zone)
        val c = controller(mode = TimelineMode.ONLINE, window = window, reduceMotion = true)
        val picked = today.plusWeeks(3)
        val waiting = c.dispatch(TimelineAction.PickDay(picked))
        assertEquals(today, waiting.anchor)
        assertEquals(picked, waiting.onlineDayRequested)
        assertTrue(waiting.awaitingCoverage)
        c.dispatch(TimelineAction.RequestCoverage(waiting.generation, window))
        assertTrue(c.state.awaitingCoverage)
        val tomorrow = today.plusDays(1)
        val refreshed = tomorrow.atTime(12, 0).atZone(zone).toInstant()
        c.dispatch(TimelineAction.Now(refreshed))
        c.dispatch(TimelineAction.OnlineBlock(picked, TimelineWindow.fixed(picked, zone)))
        assertEquals(window, c.state.coverage)
        assertEquals(now, c.state.selected)
        assertEquals(tomorrow, c.state.onlineDayRequested)
        c.dispatch(TimelineAction.OnlineBlock(tomorrow, TimelineWindow.fixed(tomorrow, zone)))
        assertEquals(refreshed, c.state.selected)
        assertEquals(tomorrow, c.state.anchor)
        assertFalse(c.state.awaitingCoverage)
    }

    @Test fun onlineFetchFailurePreservesFingerAndPendingResizeKeepsLatestRequest() {
        val window = TimelineWindow.fixed(today, zone)
        val c = controller(mode = TimelineMode.ONLINE, window = window, reduceMotion = true)
        val day = today.plusWeeks(2)
        c.dispatch(TimelineAction.BeginDrag)
        val failed = c.dispatch(TimelineAction.PickDay(day)).generation
        c.dispatch(TimelineAction.DragBy(-1.0))
        assertEquals(day, c.state.onlineDayRequested)
        c.dispatch(TimelineAction.RequestFailed(failed))
        assertEquals(TimelineMotion.DRAG, c.state.motion)
        assertNull(c.state.pendingGeneration)
        assertNull(c.state.onlineDayRequested)
        val target = now.plusSeconds(12345)
        c.dispatch(TimelineAction.Request(target))
        c.dispatch(TimelineAction.Viewport(450.0))
        c.dispatch(TimelineAction.EndDrag(true))
        assertEquals(target, c.state.selected)
    }

    @Test fun onlineEdgeFetchCannotReplaceWindowAfterLeavingEdge() {
        val window = TimelineWindow.fixed(today, zone)
        val c = controller(mode = TimelineMode.ONLINE, window = window, reduceMotion = true)
        c.dispatch(TimelineAction.Request(window.end))
        val edge = c.state.selected
        val day = edge.atZone(zone).toLocalDate()
        assertEquals(day, c.dispatch(TimelineAction.Tick, 600).onlineDayRequested)
        val loaded = TimelineAction.OnlineBlock(day, TimelineWindow.fixed(day, zone))
        c.dispatch(TimelineAction.Request(now), 601)
        c.dispatch(loaded, 602)
        assertEquals(window, c.state.coverage)
        c.dispatch(TimelineAction.Request(window.end), 603)
        c.dispatch(TimelineAction.Tick, 1203)
        c.dispatch(loaded, 1204)
        assertEquals(day, c.state.anchor)
        assertEquals(edge, c.state.selected)
    }

    @Test fun publicationAndResizeKeepActualInstantUnderCentreline() {
        val c = controller()
        c.dispatch(TimelineAction.Tick, 600)
        val earlier = TimelineWindow(full.start.minusSeconds(7200), full.end)
        val oldOffset = c.state.offset
        c.dispatch(TimelineAction.Publish(earlier))
        assertEquals(oldOffset + 36.0, c.state.offset, 1e-8)
        c.dispatch(TimelineAction.Viewport(450.0))
        assertEquals(now, c.state.selected)
        assertEquals(now, earlier.selected(c.state.offset, c.state.viewport))
    }
}
