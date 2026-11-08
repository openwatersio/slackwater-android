package io.openwaters.slackwater.android.detail

import android.animation.ValueAnimator
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.timeline.TimelineMotion
import io.openwaters.slackwater.android.core.tour.TourCoach
import io.openwaters.slackwater.android.core.tour.TourState
import io.openwaters.slackwater.android.core.tour.TourStep
import io.openwaters.slackwater.android.core.tour.tourMoonTime
import io.openwaters.slackwater.android.core.tour.tourStarsTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Where each coach mark points, in root coordinates, published by the views the tour teaches. */
internal class TourAnchors {
    val rects = mutableStateMapOf<TourStep, Rect>()
    var viewport by mutableStateOf<Rect?>(null)

    /** The moon step points at the strip, as the stars step does; only its copy and its glide differ. */
    fun target(step: TourStep): Rect? = rects[step] ?: if (step == TourStep.MOON) rects[TourStep.STARS] else null
}

internal val LocalTourAnchors = staticCompositionLocalOf<TourAnchors?> { null }

/** Publishes this view's bounds as the target of one tour step, on a detail that runs the tour. */
internal fun Modifier.tourAnchor(step: TourStep): Modifier = composed {
    val anchors = LocalTourAnchors.current ?: return@composed this
    DisposableEffect(anchors, step) { onDispose { anchors.rects.remove(step) } }
    // Unclipped, so a target scrolled out of the page still says where it is.
    onGloballyPositioned { anchors.rects[step] = Rect(it.positionInRoot(), it.size.toSize()) }
}

/** How many details for each station are composed, so replacing one with another for the same station keeps its tour. */
private object ComposedDetails {
    private val counts = mutableMapOf<StationId, Int>()
    fun enter(id: StationId) { counts[id] = (counts[id] ?: 0) + 1 }
    fun leave(id: StationId) { counts[id] = (counts[id] ?: 1) - 1 }
    fun any(id: StationId) = (counts[id] ?: 0) > 0
}

private val mainHandler = Handler(Looper.getMainLooper())

/** Under TalkBack the strip is adjusted, not swiped, so "swipe the curve" would be the wrong advice. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService<AccessibilityManager>()
    return produceState(manager?.isTouchExplorationEnabled == true, manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { value = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        try { kotlinx.coroutines.awaitCancellation() } finally { manager?.removeTouchExplorationStateChangeListener(listener) }
    }.value
}

/**
 * The tour on one station's detail: it claims an armed tour once the timeline is usable, glides the strip to tonight's
 * stars and moon, and finishes the tour when the person leaves the station. A rotation rebuilds the detail without
 * leaving it, and an activity closing or reclaimed by the system is not the person leaving, so neither ends the tour.
 */
@Composable
internal fun DetailTour(
    coach: TourCoach,
    tour: TourState,
    id: StationId,
    stationName: String,
    timeline: DetailTimeline?,
    anchors: TourAnchors,
    scroll: ScrollState,
    showSky: Boolean,
    moonCardAvailable: Boolean?,
    beforeScroll: () -> Unit,
) {
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(coach, id) {
        ComposedDetails.enter(id)
        onDispose {
            ComposedDetails.leave(id)
            // Judged once the frame settles: a detail for the same station may be taking this one's place.
            mainHandler.post {
                val closing = activity == null || activity.isChangingConfigurations || activity.isFinishing || activity.isDestroyed
                if (!closing && !ComposedDetails.any(id) && coach.state.value.station == id) coach.finish()
            }
        }
    }
    val talkBack = rememberTouchExploration()
    if (timeline != null) LaunchedEffect(timeline, tour.armed, tour.reservedFor) {
        if (!tour.armed) return@LaunchedEffect
        val state = timeline.state
        val coverage = state.coverage.start..state.coverage.end
        // The sky steps point at bodies the strip draws only with Sun, moon and stars on.
        val sky = showSky && !talkBack
        coach.begin(id,
            starsAvailable = sky && tourStarsTime(timeline.days, state.now, coverage) != null,
            moonAvailable = sky && tourMoonTime(timeline.days, state.now, coverage) != null)
    }
    val step = tour.step?.takeIf { tour.station == id } ?: return
    val hasMoonCard by rememberUpdatedState(moonCardAvailable)
    if (step == TourStep.MOON_CARD && moonCardAvailable != true) {
        // Wait for the rebuilt timeline before deciding whether a restored mark still has a tile.
        if (moonCardAvailable == false) LaunchedEffect(coach, id) {
            val current = coach.state.value
            if (current.station == id && current.step == step) coach.advance(moonCardAvailable = false)
        }
        return
    }
    // False until the glide for the step has had time to settle, which swaps the stars copy for its payoff. Saved, so
    // a rotation keeps a settled step settled; one rebuilt before it settles waits again.
    var arrived by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (!arrived) { delay(ARRIVAL_MILLIS); arrived = true }
    }
    val reduceMotion = !ValueAnimator.areAnimatorsEnabled()
    fun next() {
        arrived = false
        coach.advance(moonCardAvailable = hasMoonCard != false)
        val now = coach.state.value
        if (timeline != null && now.station == id) {
            val state = timeline.state
            val coverage = state.coverage.start..state.coverage.end
            when (now.step) {
                TourStep.STARS -> tourStarsTime(timeline.days, state.now, coverage)
                TourStep.MOON -> tourMoonTime(timeline.days, state.now, coverage)
                else -> null
            }?.let(timeline::jump)
        }
    }
    // Once the stars glide has settled, the person's own drag is the swipe the mark asks for; the moon's glide waits
    // for that finger to lift.
    if (timeline != null && step == TourStep.STARS && arrived) LaunchedEffect(timeline) {
        snapshotFlow { timeline.state.motion }.first { it == TimelineMotion.DRAG }
        next()
    }
    // Each mark brings its target to the middle of the page.
    LaunchedEffect(step) {
        withFrameNanos { }
        val target = anchors.target(step) ?: return@LaunchedEffect
        val viewport = anchors.viewport ?: return@LaunchedEffect
        beforeScroll()
        val delta = target.center.y - viewport.center.y
        if (reduceMotion) scroll.scrollBy(delta) else scroll.animateScrollBy(delta, tween(250))
    }
    TourMark(step, stationName, arrived, anchors, onNext = ::next, onSkip = coach::finish)
}

private const val ARRIVAL_MILLIS = 600L
private val CardFill = Color.Black.copy(alpha = 0.92f)
private val Paper = Color(0xfff3f5ef)
private val Foam = Color(0xffd6f2f0)
private val PointerWidth = 18.dp
private val PointerHeight = 10.dp

/**
 * A dark card that points at its target, below one in the top half of the page and above one in the bottom half, so
 * it always has room. Only the card takes touches: the page around it stays live, so the swipe it asks for reaches the
 * strip.
 */
@Composable
private fun TourMark(step: TourStep, stationName: String, arrived: Boolean, anchors: TourAnchors,
                     onNext: () -> Unit, onSkip: () -> Unit) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { anchors.viewport = it.boundsInRoot() }) {
        val viewport = anchors.viewport ?: return@Box
        val target = anchors.target(step)
        Layout(content = {
            AnimatedContent(step, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "Tour mark") { shown ->
                TourCard(shown, stationName, arrived, onNext, onSkip)
            }
        }) { measurables, constraints ->
            val gutter = 16.dp.roundToPx()
            val width = minOf(constraints.maxWidth - 2 * gutter, 360.dp.roundToPx()).coerceAtLeast(0)
            val card = measurables.single().measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = (constraints.maxWidth - width) / 2
                val gap = (PointerHeight + 2.dp).roundToPx()
                val y = when {
                    // A target that is not on the page yet: the card waits mid-page rather than stranding the tour.
                    target == null -> (constraints.maxHeight - card.height) / 2
                    target.center.y - viewport.top < viewport.height / 2 -> (target.bottom - viewport.top).toInt() + gap
                    else -> (target.top - viewport.top).toInt() - gap - card.height
                }
                card.place(x, y.coerceIn(0, (constraints.maxHeight - card.height).coerceAtLeast(0)))
            }
        }
        // The pointer, under the target's centre and clear of the card's rounded corners.
        if (target != null) Canvas(Modifier.fillMaxSize()) {
            val below = target.center.y - viewport.top < viewport.height / 2
            val width = minOf(size.width - 32.dp.toPx(), 360.dp.toPx())
            val left = (size.width - width) / 2
            val x = (target.center.x - viewport.left).coerceIn(left + 24.dp.toPx(), left + width - 24.dp.toPx())
            val half = PointerWidth.toPx() / 2
            val height = PointerHeight.toPx()
            val tip = if (below) target.bottom - viewport.top + 2.dp.toPx() else target.top - viewport.top - 2.dp.toPx()
            val base = if (below) tip + height else tip - height
            if (tip in 0f..size.height) drawPath(Path().apply {
                moveTo(x, tip); lineTo(x + half, base); lineTo(x - half, base); close()
            }, CardFill)
        }
    }
}

@Composable
private fun TourCard(step: TourStep, stationName: String, arrived: Boolean, onNext: () -> Unit, onSkip: () -> Unit) {
    val title = stringResource(when (step) {
        TourStep.READ -> R.string.tour_read_title
        TourStep.STARS -> if (arrived) R.string.tour_stars_title else R.string.tour_swipe_title
        TourStep.MOON -> R.string.tour_moon_title
        TourStep.MOON_CARD -> R.string.tour_moon_card_title
        TourStep.STAR -> R.string.tour_star_title
    })
    val body = when (step) {
        TourStep.READ -> stringResource(R.string.tour_read)
        TourStep.STARS -> if (arrived) stringResource(R.string.tour_stars, stationName) else stringResource(R.string.tour_swipe)
        TourStep.MOON -> stringResource(R.string.tour_moon)
        TourStep.MOON_CARD -> stringResource(R.string.tour_moon_card)
        TourStep.STAR -> stringResource(R.string.tour_star)
    }
    val glyph = when (step) {
        TourStep.READ -> "⌇"
        TourStep.STARS -> if (arrived) "✦" else "↔"
        TourStep.MOON -> "☾"
        TourStep.MOON_CARD -> "◐"
        TourStep.STAR -> "☆"
    }
    Surface(shape = RoundedCornerShape(16.dp), color = CardFill, modifier = Modifier.testTag("tour-mark")) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(glyph, color = Foam.copy(alpha = 0.7f), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.width(32.dp).padding(top = 2.dp).clearAndSetSemantics {})
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Read out as each mark replaces the last, since nothing else moves TalkBack to it.
                Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text(title, color = Paper, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(body, color = Foam.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp).testTag("tour-next")) {
                    Text(stringResource(if (step == TourStep.STAR) R.string.tour_done else R.string.tour_next),
                        color = Paper, fontWeight = FontWeight.SemiBold)
                }
            }
            val skip = stringResource(R.string.tour_skip)
            // A small circle, as iOS draws it, inside a full-size touch target.
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onSkip)
                .semantics { contentDescription = skip }.testTag("tour-skip"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(28.dp).background(Foam.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("\u2715", color = Foam.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}
