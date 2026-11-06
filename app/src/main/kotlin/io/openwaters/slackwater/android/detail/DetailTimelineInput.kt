package io.openwaters.slackwater.android.detail

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.timeline.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

internal fun Modifier.detailTimelineInput(
    timeline: DetailTimeline, accessibleValue: String, onPress: ((Instant) -> Unit)? = null,
    onAlert: ((Instant) -> Unit)? = onPress, onPickDay: ((LocalDate) -> Unit)? = null,
): Modifier = composed {
    val density = LocalDensity.current.density
    val decay = rememberSplineBasedDecay<Float>()
    val haptic = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    val pickDay by rememberUpdatedState(onPickDay)
    val earlier = stringResource(R.string.alert_plot_earlier)
    val later = stringResource(R.string.alert_plot_later)
    val next = stringResource(R.string.detail_timeline_next)
    val previous = stringResource(R.string.detail_timeline_previous)
    val alert = stringResource(R.string.alert_set_action)
    val label = stringResource(if (timeline.series is PredictionSeries.Tide) R.string.detail_timeline_tide
        else R.string.detail_timeline_current)
    var focused by remember { mutableStateOf(false) }

    fun step(forward: Boolean): Boolean {
        val before = timeline.state.selected
        val target = timeline.state.coverage.clamp(before.plusSeconds(if (forward) 300 else -300), timeline.state.viewport)
        if (before == target) return false
        timeline.direct(target)
        return true
    }

    fun event(forward: Boolean): Boolean {
        val at = timeline.state.selected
        val stop = if (forward) timeline.targets.next(at) else timeline.targets.previous(at)
        if (stop == null) return false
        timeline.direct(stop)
        return true
    }

    this.testTag("detailTimeline")
        .onSizeChanged { timeline.dispatch(TimelineAction.Viewport(it.width / density.toDouble())) }
        .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent {
            if (it.type != KeyEventType.KeyDown) false
            else when (it.key) {
                Key.DirectionLeft -> step(false)
                Key.DirectionRight -> step(true)
                else -> false
            }
        }
        .focusable()
        .semantics {
            contentDescription = label
            stateDescription = accessibleValue
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { timeline.state.offset.toFloat() },
                maxValue = { (timeline.state.coverage.width - timeline.state.viewport).coerceAtLeast(0.0).toFloat() },
            )
            scrollBy { x, _ -> x != 0f && step(x > 0) }
            customActions = buildList {
                add(CustomAccessibilityAction(earlier) { step(false) })
                add(CustomAccessibilityAction(later) { step(true) })
                add(CustomAccessibilityAction(previous) { event(false) })
                add(CustomAccessibilityAction(next) { event(true) })
                if (onAlert != null && timeline.mode == TimelineMode.CONTINUOUS) add(CustomAccessibilityAction(alert) {
                    timeline.direct(timeline.state.selected)
                    onAlert(timeline.state.selected)
                    true
                })
            }
        }
        .draggable(
            state = rememberDraggableState { delta ->
                timeline.dispatch(TimelineAction.DragBy(delta / density.toDouble()))
            },
            orientation = Orientation.Horizontal,
            onDragStarted = { timeline.dispatch(TimelineAction.BeginDrag) },
            onDragStopped = { velocity ->
                val fling = abs(velocity / density) >= 50f
                timeline.dispatch(TimelineAction.EndDrag(fling))
                if (timeline.state.motion == TimelineMotion.MOMENTUM) {
                    val generation = timeline.state.generation
                    var previousOffset = 0f
                    AnimationState(0f, velocity / density).animateDecay(decay) {
                        val before = timeline.state.selected
                        val delta = value - previousOffset
                        previousOffset = value
                        timeline.dispatch(TimelineAction.MomentumBy(generation, delta.toDouble()))
                        if (timeline.state.generation != generation || before == timeline.state.selected && delta != 0f)
                            cancelAnimation()
                    }
                    timeline.dispatch(TimelineAction.EndMomentum(generation))
                }
            },
        )
        .pointerInput(timeline, haptic) {
            fun timeAt(x: Float): Instant {
                val state = timeline.state
                return state.coverage.time(state.coverage.x(state.selected) + (x - size.width / 2f) / density)
            }
            detectTapGestures(
                onLongPress = { point ->
                    if (press != null && timeline.mode == TimelineMode.CONTINUOUS && point.y < size.height - 62.dp.toPx()) {
                        timeline.dispatch(TimelineAction.Tap(timeAt(point.x), press = true))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        press?.invoke(timeline.state.selected)
                    }
                },
                onTap = { point ->
                    val target = timeAt(point.x)
                    if (point.y < size.height - 62.dp.toPx()) timeline.dispatch(TimelineAction.Tap(target))
                    else {
                        timeline.dispatch(TimelineAction.Stop)
                        val (day, sun) = dayHit(timeline, target)
                        if (sun != null) timeline.dispatch(TimelineAction.Request(timeline.state.coverage.clamp(sun, timeline.state.viewport)))
                        else if (timeline.mode != TimelineMode.PREVIEW) pickDay?.invoke(day)
                    }
                },
            )
        }
}

private fun dayHit(timeline: DetailTimeline, time: Instant): Pair<LocalDate, Instant?> {
    var bestDate = time.atZone(timeline.zone).toLocalDate()
    var bestDistance = Duration.ofDays(100)
    var hit: Instant? = null
    for (day in timeline.days) {
        val rise = day.sunrise
        val set = day.sunset
        val middle = day.dateAnchor(timeline.days, timeline.zone)
        val distance = Duration.between(time, middle).abs()
        if (distance <= bestDistance) {
            bestDate = day.date
            bestDistance = distance
            hit = null
        }
        if (timeline.showSky) for (sun in listOfNotNull(rise, set)) {
            val sunDistance = Duration.between(time, sun).abs()
            if (sunDistance < bestDistance) {
                bestDistance = sunDistance
                hit = sun
            }
        }
    }
    return bestDate to hit
}
