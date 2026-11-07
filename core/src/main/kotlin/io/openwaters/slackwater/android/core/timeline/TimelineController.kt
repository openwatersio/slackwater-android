package io.openwaters.slackwater.android.core.timeline

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TimelineMode { CONTINUOUS, ONLINE, PREVIEW }
enum class TimelineMotion { INITIAL_POSITIONING, OPENING, HINGE_FOLLOW, DRAG, MOMENTUM, RIDE, MAGNETIC_SETTLE, RESTING }

data class TimelineMove(val from: Instant, val to: Instant)
data class TimelineState(
    val anchor: LocalDate,
    val now: Instant,
    val selected: Instant,
    val offset: Double,
    val viewport: Double,
    val coverage: TimelineWindow,
    val motion: TimelineMotion,
    val generation: Long = 0,
    val pendingGeneration: Long? = null,
    val awaitingCoverage: Boolean = false,
    val move: TimelineMove? = null,
    val pillsVisible: Boolean = false,
    val settled: Boolean = false,
    val onlineDayRequested: LocalDate? = null,
) {
    val home: Boolean get() = isNearSessionNow(selected, now)
    val busy: Boolean get() = awaitingCoverage || motion != TimelineMotion.RESTING || !settled
}

fun isNearSessionNow(position: Instant, now: Instant): Boolean =
    Duration.between(now, position).abs() <= Duration.ofSeconds(200)

sealed interface TimelineAction {
    data object Tick : TimelineAction
    data object BeginHingeFollow : TimelineAction
    data object BeginDrag : TimelineAction
    data class DragBy(val contentDeltaDp: Double) : TimelineAction
    data class EndDrag(val momentum: Boolean) : TimelineAction
    data class MomentumBy(val generation: Long, val contentDeltaDp: Double) : TimelineAction
    data class EndMomentum(val generation: Long) : TimelineAction
    // Standard scroll animation supplied by the host. A final progress of one parks the exact target.
    data class AnimationFrame(val generation: Long, val progress: Double) : TimelineAction
    data class Request(
        val target: Instant,
        val direct: Boolean = false,
        val coverage: TimelineWindow? = null,
        val anchor: LocalDate? = null,
    ) : TimelineAction
    data class PrepareRequest(val target: Instant, val direct: Boolean = false, val anchor: LocalDate? = null) : TimelineAction
    data class RequestCoverage(val generation: Long, val window: TimelineWindow) : TimelineAction
    data class RequestFailed(val generation: Long) : TimelineAction
    data class Now(val instant: Instant, val coverage: TimelineWindow? = null) : TimelineAction
    data class Foreground(val instant: Instant, val coverage: TimelineWindow? = null) : TimelineAction
    data class PickDay(val day: LocalDate, val coverage: TimelineWindow? = null) : TimelineAction
    data class Tap(val target: Instant, val press: Boolean = false) : TimelineAction
    data object Stop : TimelineAction
    data class Viewport(val width: Double) : TimelineAction
    data class Publish(val window: TimelineWindow) : TimelineAction
    data class OnlineBlock(val day: LocalDate, val window: TimelineWindow) : TimelineAction
    data class Targets(val value: MagneticTargets) : TimelineAction
    data class SelectionFeedback(val target: Instant) : TimelineAction
}

class TimelineController(
    now: Instant,
    private val zone: ZoneId,
    coverage: TimelineWindow,
    viewport: Double,
    val mode: TimelineMode = TimelineMode.CONTINUOUS,
    initialSelection: Instant = now.minusSeconds(7200),
    private val reduceMotion: Boolean = false,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    anchor: LocalDate = openingAnchor(now, initialSelection, zone),
    allowOpening: Boolean = true,
) {
    init { require(viewport >= 0 && viewport.isFinite()) }

    private var openedAt = clockMillis()
    private var lastActivity = openedAt
    private var targets = MagneticTargets()
    private var pending: TimelineAction.Request? = null
    val pendingTarget: Instant? get() = pending?.target
    private val opening = allowOpening && Duration.between(initialSelection, now.minusSeconds(7200)).abs() <= Duration.ofSeconds(2)
    private val first = coverage.clamp(if (opening && reduceMotion) now else initialSelection, viewport)
    var state = TimelineState(
        anchor, now, first, coverage.offset(first, viewport), viewport, coverage,
        when {
            viewport == 0.0 -> TimelineMotion.INITIAL_POSITIONING
            opening && !reduceMotion -> TimelineMotion.OPENING
            else -> TimelineMotion.RESTING
        },
        move = if (opening && !reduceMotion) TimelineMove(first, coverage.clamp(now, viewport)) else null,
    )
        private set

    fun dispatch(action: TimelineAction, elapsedMillis: Long = clockMillis()): TimelineState {
        when (action) {
            TimelineAction.Tick -> tick(elapsedMillis)
            TimelineAction.BeginHingeFollow -> if (!reduceMotion && state.viewport > 0 &&
                state.motion == TimelineMotion.RESTING && state.pendingGeneration == null) {
                val target = state.selected
                val lead = state.coverage.clamp(target.plusSeconds(6 * 3600), state.viewport)
                if (lead > target) {
                    stop(elapsedMillis)
                    position(lead, elapsedMillis)
                    state = state.copy(motion = TimelineMotion.HINGE_FOLLOW, move = TimelineMove(lead, target))
                }
            }
            TimelineAction.BeginDrag -> {
                stop(elapsedMillis)
                state = state.copy(motion = TimelineMotion.DRAG)
            }
            is TimelineAction.DragBy -> if (state.motion == TimelineMotion.DRAG) dragBy(action.contentDeltaDp, elapsedMillis)
            is TimelineAction.EndDrag -> if (state.motion == TimelineMotion.DRAG) {
                val waiting = pending
                state = state.copy(motion = TimelineMotion.RESTING)
                when {
                    state.awaitingCoverage -> activity(elapsedMillis)
                    waiting != null -> move(waiting, elapsedMillis)
                    action.momentum -> state = state.copy(motion = TimelineMotion.MOMENTUM)
                    else -> finishGesture(elapsedMillis)
                }
            }
            is TimelineAction.MomentumBy -> if (isMomentum(action.generation)) {
                dragBy(action.contentDeltaDp, elapsedMillis)
            }
            is TimelineAction.EndMomentum -> if (isMomentum(action.generation)) finishGesture(elapsedMillis)
            is TimelineAction.AnimationFrame -> if (action.generation == state.generation &&
                state.motion in setOf(TimelineMotion.RIDE, TimelineMotion.MAGNETIC_SETTLE, TimelineMotion.HINGE_FOLLOW)
            ) frame(action.progress, elapsedMillis)
            is TimelineAction.Request -> explicit(action, elapsedMillis)
            is TimelineAction.PrepareRequest -> prepare(TimelineAction.Request(action.target, action.direct, anchor = action.anchor), elapsedMillis)
            is TimelineAction.RequestCoverage -> if (state.awaitingCoverage && action.generation == state.generation) {
                completeCoverage(action.window, elapsedMillis)
            }
            is TimelineAction.RequestFailed -> if (state.awaitingCoverage && action.generation == state.generation) {
                val dragging = state.motion == TimelineMotion.DRAG
                stop(elapsedMillis)
                if (dragging) state = state.copy(motion = TimelineMotion.DRAG)
            }
            is TimelineAction.Tap -> {
                stop(elapsedMillis)
                request(TimelineAction.Request(targets.capture(action.target) ?: action.target, action.press), elapsedMillis)
            }
            TimelineAction.Stop -> stop(elapsedMillis)
            is TimelineAction.Foreground -> if (state.home) {
                goHome(action.instant, action.coverage, elapsedMillis)
            } else state = state.copy(now = action.instant)
            is TimelineAction.Now -> goHome(action.instant, action.coverage, elapsedMillis)
            is TimelineAction.PickDay -> if (mode != TimelineMode.PREVIEW) {
                val window = TimelineWindow.fixed(action.day, zone)
                val target = if (state.selected in window.start..window.end) state.selected
                    else action.day.atTime(12, 0).atZone(zone).toInstant()
                requestDay(target, action.day, action.coverage, elapsedMillis)
            }
            is TimelineAction.Viewport -> if (state.viewport != action.width) {
                require(action.width >= 0 && action.width.isFinite())
                val previousWidth = state.viewport
                val initial = state.motion == TimelineMotion.INITIAL_POSITIONING
                if (state.motion == TimelineMotion.MOMENTUM || state.motion == TimelineMotion.MAGNETIC_SETTLE) stop(elapsedMillis)
                state = state.copy(viewport = action.width)
                position(state.selected, elapsedMillis)
                if (initial && action.width > 0) {
                    openedAt = elapsedMillis
                    activity(elapsedMillis)
                    state = state.copy(
                        motion = if (state.move == null) TimelineMotion.RESTING else TimelineMotion.OPENING,
                        move = state.move?.let { TimelineMove(state.selected, state.coverage.clamp(it.to, action.width)) },
                    )
                } else if (mode == TimelineMode.ONLINE) activity(elapsedMillis)
                resizePending(previousWidth, elapsedMillis)
            }
            is TimelineAction.Publish -> {
                // An origin change is safe only at rest. Explicit moves can replace coverage atomically.
                if (!state.busy || action.window.start == state.coverage.start && action.window.end >= state.coverage.end) {
                    state = state.copy(coverage = action.window)
                    position(state.selected, elapsedMillis)
                }
            }
            is TimelineAction.OnlineBlock -> if (action.day == state.onlineDayRequested &&
                action.window == TimelineWindow.fixed(action.day, zone)
            ) {
                if (state.awaitingCoverage) completeCoverage(action.window, elapsedMillis)
                else {
                    state = state.copy(coverage = action.window, anchor = action.day, onlineDayRequested = null)
                    position(state.selected, elapsedMillis)
                }
            }
            is TimelineAction.Targets -> targets = action.value
            is TimelineAction.SelectionFeedback -> if (!state.awaitingCoverage && state.motion == TimelineMotion.RESTING &&
                Duration.between(action.target, state.selected).abs() > Duration.ofSeconds(200)
            ) position(action.target, elapsedMillis)
        }
        return state
    }

    private fun isMomentum(generation: Long) = state.motion == TimelineMotion.MOMENTUM && state.generation == generation

    private fun goHome(now: Instant, coverage: TimelineWindow?, at: Long) {
        state = state.copy(now = now)
        requestDay(now, now.atZone(zone).toLocalDate(), coverage, at)
    }

    private fun requestDay(target: Instant, day: LocalDate, coverage: TimelineWindow?, at: Long) {
        val move = TimelineAction.Request(target, coverage = coverage, anchor = day)
        if (mode == TimelineMode.ONLINE && (coverage ?: state.coverage) != TimelineWindow.fixed(day, zone)) prepare(move, at)
        else explicit(move, at)
    }

    private fun explicit(move: TimelineAction.Request, at: Long) {
        if (mode == TimelineMode.CONTINUOUS && move.coverage == null &&
            (state.viewport == 0.0 || !canCenter(state.coverage, move.target, state.viewport))
        ) prepare(move, at)
        else request(move, at)
    }

    private fun canCenter(window: TimelineWindow, target: Instant, width: Double) =
        window.width >= width && window.clamp(target, width) == target

    private fun resizePending(previousWidth: Double, at: Long) {
        val request = pending ?: return
        if (mode != TimelineMode.CONTINUOUS) return
        val width = state.viewport
        val window = request.coverage ?: state.coverage
        if (state.awaitingCoverage && previousWidth == 0.0 && canCenter(state.coverage, request.target, width)) {
            completeCoverage(state.coverage, at)
            return
        }
        val needsMoreData = if (state.awaitingCoverage) width > previousWidth
            else canCenter(window, request.target, previousWidth) && !canCenter(window, request.target, width)
        if (width == 0.0 || needsMoreData) prepare(request.copy(coverage = null), at)
    }

    private fun prepare(request: TimelineAction.Request, at: Long) {
        val dragging = state.motion == TimelineMotion.DRAG
        stop(at)
        pending = request
        state = state.copy(
            awaitingCoverage = true, pendingGeneration = state.generation,
            onlineDayRequested = request.anchor.takeIf { mode == TimelineMode.ONLINE },
            motion = if (dragging) TimelineMotion.DRAG else TimelineMotion.RESTING,
        )
    }

    private fun completeCoverage(window: TimelineWindow, at: Long) {
        val request = requireNotNull(pending)
        if (mode == TimelineMode.ONLINE && request.anchor != null && window != TimelineWindow.fixed(request.anchor, zone)) return
        if (mode == TimelineMode.CONTINUOUS && (state.viewport == 0.0 || !canCenter(window, request.target, state.viewport))) {
            prepare(request.copy(coverage = null), at)
            return
        }
        val ready = request.copy(coverage = window)
        state = state.copy(awaitingCoverage = false, onlineDayRequested = null)
        if (state.motion == TimelineMotion.DRAG) pending = ready else move(ready, at)
    }

    private fun dragBy(delta: Double, at: Long) {
        val window = state.coverage
        position(window.time(window.x(state.selected) - delta), at)
    }

    private fun finishGesture(at: Long) {
        val captured = targets.capture(state.selected)
        if (captured == null) stop(at)
        else {
            state = state.copy(motion = TimelineMotion.RESTING)
            request(TimelineAction.Request(captured), at)
            if (state.motion == TimelineMotion.RIDE) state = state.copy(motion = TimelineMotion.MAGNETIC_SETTLE)
        }
    }

    private fun request(request: TimelineAction.Request, at: Long) {
        state = state.copy(generation = state.generation + 1, awaitingCoverage = false, onlineDayRequested = null)
        if (state.motion == TimelineMotion.DRAG) {
            pending = request
            state = state.copy(pendingGeneration = state.generation)
        } else move(request, at)
    }

    private fun move(request: TimelineAction.Request, at: Long) {
        activity(at)
        val window = request.coverage ?: state.coverage
        val from = state.selected
        val target = window.clamp(request.target, state.viewport)
        pending = null
        state = state.copy(
            coverage = window, move = null,
            motion = if (state.viewport == 0.0) TimelineMotion.INITIAL_POSITIONING else TimelineMotion.RESTING,
            anchor = request.anchor ?: state.anchor, pendingGeneration = null,
        )
        if (state.viewport > 0 && shouldAnimateTimelineMove(
                from, target, reduceMotion, request.direct, window.clamp(from, state.viewport) == from,
            )
        ) {
            position(from, at)
            state = state.copy(motion = TimelineMotion.RIDE, move = TimelineMove(from, target))
        } else position(target, at)
    }

    private fun stop(at: Long) {
        pending = null
        activity(at)
        state = state.copy(
            generation = state.generation + 1, motion = TimelineMotion.RESTING,
            pendingGeneration = null, awaitingCoverage = false, move = null,
        )
    }

    private fun activity(at: Long) {
        lastActivity = at
        state = state.copy(
            pillsVisible = false, settled = false,
            onlineDayRequested = pending?.anchor.takeIf { mode == TimelineMode.ONLINE && state.awaitingCoverage },
        )
    }

    private fun position(target: Instant, at: Long) {
        val actual = state.coverage.clamp(target, state.viewport)
        if (actual != state.selected) activity(at)
        state = state.copy(selected = actual, offset = state.coverage.offset(actual, state.viewport))
    }

    private fun frame(progress: Double, at: Long) {
        val move = state.move ?: return
        if (progress >= 1.0) {
            position(move.to, at)
            state = state.copy(motion = TimelineMotion.RESTING, move = null)
            activity(at)
        } else {
            val nanos = Duration.between(move.from, move.to).toNanos()
            position(move.from.plusNanos((nanos * progress.coerceAtLeast(0.0)).toLong()), at)
        }
    }

    private fun tick(at: Long) {
        if (state.motion == TimelineMotion.OPENING) frame((at - openedAt) / 650.0, at)
        if (state.motion != TimelineMotion.RESTING || state.awaitingCoverage) return
        val quiet = at - lastActivity
        if (quiet >= 450 && !state.pillsVisible) state = state.copy(pillsVisible = true)
        if (quiet < 600 || state.settled) return
        state = state.copy(settled = true)
        val day = state.selected.atZone(zone).toLocalDate()
        val window = TimelineWindow.fixed(state.anchor, zone)
        when (mode) {
            TimelineMode.CONTINUOUS -> if (state.selected !in window.start..window.end) state = state.copy(anchor = day)
            TimelineMode.ONLINE -> if (day != state.anchor && state.viewport > 0) {
                val x = window.x(state.selected)
                val edge = state.viewport / 2 + 18.0
                if (x <= edge || window.width - x <= edge) state = state.copy(onlineDayRequested = day)
            }
            TimelineMode.PREVIEW -> Unit
        }
    }
}

private fun openingAnchor(now: Instant, initial: Instant, zone: ZoneId): LocalDate {
    val today = now.atZone(zone).toLocalDate()
    val window = TimelineWindow.fixed(today, zone)
    return if (initial in window.start..window.end) today else initial.atZone(zone).toLocalDate()
}
