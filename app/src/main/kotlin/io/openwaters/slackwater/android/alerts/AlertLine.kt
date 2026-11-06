package io.openwaters.slackwater.android.alerts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.openwaters.slackwater.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChartAlertSelection(
    val station: StationIdentity, val capability: StationCapabilities, val trigger: AlertTrigger,
    val moment: Instant, val nextEvent: Instant?,
)

data class AlertEditorDraft(
    val rule: AlertRule, val station: StationIdentity, val boundMoment: Instant?, val existing: Boolean = false,
)

@Composable
internal fun AlertTrigger.label(): String = when (this) {
    AlertTrigger.SlackWindowOpens -> stringResource(R.string.alert_slack_window)
    AlertTrigger.Slack -> stringResource(R.string.alert_slack)
    AlertTrigger.Eclipse -> stringResource(R.string.alert_eclipse)
    is AlertTrigger.CurrentPeak -> stringResource(if (flood) R.string.alert_flood else R.string.alert_ebb)
    is AlertTrigger.TideExtreme -> stringResource(if (high) {
        if (ofDay) R.string.alert_higher_high else R.string.alert_high
    } else if (ofDay) R.string.alert_lower_low else R.string.alert_low)
    is AlertTrigger.TideCrossing -> stringResource(if (rising) R.string.alert_rise else R.string.alert_fall,
        java.text.DecimalFormat("0.##").format(heightM))
}

internal fun StationIdentity.timeLabel(time: Instant): String =
    DateTimeFormatter.ofPattern("EEE HH:mm z").withZone(timezone?.let(ZoneId::of) ?: ZoneOffset.UTC).format(time)

internal fun ChartAlertSelection.lineDraft(parked: Boolean, rules: List<AlertRule>, now: Instant): AlertEditorDraft? {
    if (!capability.supports(PredictionPurpose.ALERT)) return null
    val matches = rules.filter { it.stationId == station.id && it.trigger == trigger }
    val minute = moment.truncatedTo(ChronoUnit.MINUTES)
    val set = matches.firstOrNull { it.once?.truncatedTo(ChronoUnit.MINUTES) == minute }
        ?: if (parked) null else matches.filter { it.once?.let { time -> time >= now } == true }.minByOrNull { it.once!! }
    val existing = set ?: matches.firstOrNull { it.once == null }
    val bound = if (parked) minute else nextEvent?.takeIf { it > now }
    return AlertEditorDraft(existing ?: AlertRule.forEditor(station.id, trigger, bound), station, existing?.once ?: bound, existing != null)
}

@Composable
fun AlertLine(selection: ChartAlertSelection?, parked: Boolean, rules: List<AlertRule>, now: Instant,
              onEdit: (AlertEditorDraft) -> Unit) {
    val draft = selection?.lineDraft(parked, rules, now) ?: return
    val subject = draft.rule.trigger.label()
    val label = when {
        draft.existing -> stringResource(R.string.alert_set_subject, draft.rule.once?.let {
            stringResource(R.string.alert_subject_time, subject, draft.station.timeLabel(it))
        } ?: stringResource(R.string.alert_every, subject))
        parked -> stringResource(R.string.alert_pressed_subject, subject, draft.station.timeLabel(selection.moment))
        else -> stringResource(R.string.alert_set_action)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        TextButton(onClick = { onEdit(draft) }) { Text(label) }
    }
}

/** Attach to the detail plot. Hit testing returns null on the day row. Parking stops chart motion. */
@Composable
fun Modifier.chartAlertPress(
    supported: Boolean, selectionAt: (Offset) -> ChartAlertSelection?,
    center: ChartAlertSelection?, onPark: (ChartAlertSelection) -> Unit,
): Modifier {
    if (!supported) return this
    val select by rememberUpdatedState(selectionAt)
    val park by rememberUpdatedState(onPark)
    val haptic = LocalHapticFeedback.current
    val actionLabel = stringResource(R.string.alert_set_action)
    return semantics {
        center?.takeIf { it.capability.supports(PredictionPurpose.ALERT) }?.let {
            customActions = listOf(CustomAccessibilityAction(actionLabel) { park(it); true })
        }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val selected = select(down.position) ?: return@awaitEachGesture
            if (!selected.capability.supports(PredictionPurpose.ALERT)) return@awaitEachGesture
            val canceled = withTimeoutOrNull(500L) {
                do {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                } while (change.pressed && !change.isConsumed && (change.position - down.position).getDistance() <= viewConfiguration.touchSlop)
                true
            }
            if (canceled == null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                park(selected)
                do {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}

internal fun markerMinutes(events: List<AlertOccurrence>, tile: TimeSpan): List<Instant> =
    events.map { it.event.truncatedTo(ChronoUnit.MINUTES) }
        .filter { it >= tile.start && it < tile.end }.distinct().sorted()

/** Call once for the loaded detail window, sharing the result across all its chart tiles. */
@Composable
fun rememberAlertMarkers(
    detail: Boolean, selection: ChartAlertSelection, rules: List<AlertRule>, window: TimeSpan,
    thresholdKnots: Double, repository: PredictionRepository,
): List<Instant> {
    val station = selection.station
    if (!detail || !selection.capability.supports(PredictionPurpose.ALERT)) return emptyList()
    val relevant = rules.filter { it.stationId == station.id && it.enabled && !it.heightNeedsReview }
    val markers = remember(station, relevant, window, thresholdKnots, repository) { mutableStateOf(emptyList<Instant>()) }
    LaunchedEffect(markers) {
        markers.value = withContext(Dispatchers.Default) {
            val zone = station.timezone?.let(ZoneId::of) ?: ZoneOffset.UTC
            markerMinutes(relevant.flatMap {
                OccurrenceResolver.resolve(it, repository, window, zone, station.latitude, station.longitude, thresholdKnots)
            }, window)
        }
    }
    return markers.value
}

/** Draw above a detail tile's curve. A shared boundary belongs to the following tile. */
@Composable
fun AlertMarkerOverlay(markers: List<Instant>, tile: TimeSpan, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.clipToBounds()) {
        val widthMs = Duration.between(tile.start, tile.end).toMillis()
        if (widthMs <= 0) return@Canvas
        val radius = 4.dp.toPx()
        val stroke = 1.dp.toPx()
        for (time in markers) {
            if (time < tile.start || time >= tile.end) continue
            val x = size.width * (Duration.between(tile.start, time).toMillis().toDouble() / widthMs).toFloat()
            drawLine(ink.copy(alpha = 0.65f), Offset(x, radius * 2.5f), Offset(x, size.height), stroke)
            val bell = Path().apply {
                moveTo(x - radius, radius * 1.9f)
                cubicTo(x - radius * 0.5f, radius * 1.5f, x - radius, stroke, x, stroke)
                cubicTo(x + radius, stroke, x + radius * 0.5f, radius * 1.5f, x + radius, radius * 1.9f)
                close()
            }
            drawPath(bell, ink, style = Stroke(stroke))
            drawCircle(ink, stroke, Offset(x, radius * 2.2f))
        }
    }
}
