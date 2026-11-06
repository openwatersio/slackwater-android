package io.openwaters.slackwater.android.stations

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** An action a swipe reveals behind a card. It runs only when tapped, so a full swipe never triggers it. */
class SwipeAction(val label: String, val color: Color, val onAction: () -> Unit)

private enum class Reveal { LEADING, SETTLED, TRAILING }

private val ActionWidth = 112.dp

@Composable
fun SwipeRow(leading: SwipeAction?, trailing: SwipeAction?, content: @Composable () -> Unit) {
    if (leading == null && trailing == null) return content()
    val width = with(LocalDensity.current) { ActionWidth.toPx() }
    val state = remember(leading != null, trailing != null, width) {
        AnchoredDraggableState(
            initialValue = Reveal.SETTLED,
            anchors = DraggableAnchors {
                Reveal.SETTLED at 0f
                if (leading != null) Reveal.LEADING at width
                if (trailing != null) Reveal.TRAILING at -width
            },
        )
    }
    val scope = rememberCoroutineScope()
    fun run(action: SwipeAction) {
        scope.launch { state.animateTo(Reveal.SETTLED) }
        action.onAction()
    }
    Box {
        // The buttons exist only while a swipe has moved the card, so keyboard focus never lands on one hidden behind
        // it. They stay out of the accessibility tree; the card carries the same actions itself.
        val moved by remember(state) { derivedStateOf { state.offset.let { !it.isNaN() && it != 0f } } }
        if (moved) Box(Modifier.matchParentSize().clearAndSetSemantics {}) {
            leading?.let { action ->
                ActionButton(action, Modifier.align(Alignment.CenterStart)) { run(action) }
            }
            trailing?.let { action ->
                ActionButton(action, Modifier.align(Alignment.CenterEnd)) { run(action) }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(state.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(state, Orientation.Horizontal),
        ) { content() }
    }
}

@Composable
private fun ActionButton(action: SwipeAction, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = action.color, contentColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.width(ActionWidth).fillMaxHeight().padding(4.dp),
    ) { Text(action.label) }
}
