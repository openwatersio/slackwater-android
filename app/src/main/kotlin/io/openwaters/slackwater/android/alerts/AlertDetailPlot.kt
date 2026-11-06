package io.openwaters.slackwater.android.alerts

import androidx.compose.foundation.focusable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.EventList
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.data.HeightUnits
import io.openwaters.slackwater.android.data.ReadingPreferences
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlin.math.*

private const val METRES_PER_FOOT = 0.3048

private fun displayHeight(metres: Double, units: HeightUnits) =
    if (units == HeightUnits.IMPERIAL) metres / METRES_PER_FOOT else metres

private fun eventStops(series: PredictionSeries) = when (series) {
    is PredictionSeries.Tide -> series.events.map { it.time to AlertTrigger.TideExtreme(it.kind == ExtremeKind.HIGH) }
    is PredictionSeries.Derived -> series.slacks.map { it.time to AlertTrigger.Slack }
    is PredictionSeries.Current -> series.events.map { it.time to when (it.kind) {
        CurrentEventKind.SLACK -> AlertTrigger.Slack
        CurrentEventKind.MAX_FLOOD -> AlertTrigger.CurrentPeak(true)
        CurrentEventKind.MAX_EBB -> AlertTrigger.CurrentPeak(false)
    } }
}

internal fun plotSelection(
    series: PredictionSeries, offer: ChartAlertSelection, time: Instant, units: HeightUnits,
    eclipseContacts: List<Instant> = emptyList(), fromNotification: Boolean = false,
): ChartAlertSelection? {
    // Notification destinations carry an occurrence at minute precision.
    val contact = eclipseContacts.firstOrNull {
        (fromNotification && it.truncatedTo(ChronoUnit.MINUTES) == time) || Duration.between(it, time).abs() <= Duration.ofSeconds(1)
    }
    if (contact != null) return offer.copy(moment = contact, trigger = AlertTrigger.Eclipse)
    val nearest = eventStops(series).minByOrNull { Duration.between(it.first, time).abs() }
    if (nearest != null && Duration.between(nearest.first, time).abs() <= Duration.ofMinutes(15)) {
        val trigger = if (series is PredictionSeries.Current && nearest.second == AlertTrigger.Slack)
            AlertTrigger.SlackWindowOpens else nearest.second
        return offer.copy(moment = nearest.first, trigger = trigger)
    }
    val trigger = when (series) {
        is PredictionSeries.Tide -> {
            if (series.points.size < 2 || time < series.points.first().time || time > series.points.last().time) return null
            val metres = series.points.heightAt(time) ?: return null
            val index = series.points.indexOfFirst { it.time >= time }.coerceAtLeast(1)
            val rising = series.rates.rateAt(time)?.let { it > 0 }
                ?: (series.points[index].heightMetres > series.points[index - 1].heightMetres)
            val value = displayHeight(metres, units)
            val scale = if (units == HeightUnits.IMPERIAL) 10.0 else 100.0
            val rounded = sign(value) * floor(abs(value) * scale + 0.5) / scale
            AlertTrigger.TideCrossing(if (units == HeightUnits.IMPERIAL) rounded * METRES_PER_FOOT else rounded, rising)
        }
        is PredictionSeries.Current -> AlertTrigger.SlackWindowOpens
        is PredictionSeries.Derived -> AlertTrigger.Slack
    }
    return offer.copy(moment = time, trigger = trigger)
}

@Composable
fun AlertDetailPlot(
    ready: PredictionResult.Ready, selection: ChartAlertSelection, rules: List<AlertRule>,
    thresholdKnots: Double, repository: PredictionRepository, now: Instant, onEdit: (AlertEditorDraft) -> Unit,
    center: Instant? = null, heightUnits: HeightUnits = ReadingPreferences().heightUnits(),
) {
    var parkedTime by remember(selection.station.id, center) { mutableStateOf<Instant?>(null) }
    val coverage = ready.coverage
    val series = ready.series
    val contacts by produceState(emptyList<Instant>(), selection.station, coverage) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            SunWindows.eclipseContacts(coverage, selection.station.latitude, selection.station.longitude)
        }
    }
    val cursor = parkedTime?.takeIf { it in coverage }
    val parked = cursor?.let { plotSelection(series, selection, it, heightUnits, contacts) }
    val focus = cursor ?: center
    val readingTime = focus ?: now
    val width = minOf(Duration.ofHours(12), Duration.between(coverage.start, coverage.end))
    if (width.toMillis() <= 0) return
    val start = readingTime.minus(width.dividedBy(2)).coerceIn(coverage.start, coverage.end.minus(width))
    val window = TimeSpan(start, start.plus(width))
    val title = when (series) {
        is PredictionSeries.Tide -> stringResource(R.string.alert_plot_tide, if (heightUnits == HeightUnits.IMPERIAL) "ft" else "m")
        is PredictionSeries.Current -> stringResource(R.string.alert_plot_current)
        is PredictionSeries.Derived -> stringResource(R.string.alert_plot_derived)
    }
    val samples = remember(series, heightUnits) {
        when (series) {
            is PredictionSeries.Tide -> series.points.map { it.time to displayHeight(it.heightMetres, heightUnits) }
            is PredictionSeries.Current -> series.points.map { it.time to it.speedKnots }
            is PredictionSeries.Derived -> series.slacks.map { it.time to 0.0 }
        }
    }
    val min = minOf(0.0, samples.minOfOrNull { it.second } ?: 0.0)
    val max = maxOf(0.0, samples.maxOfOrNull { it.second } ?: 0.0)
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    val ink = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.outline
    var pixels by remember { mutableIntStateOf(0) }
    val loadedMarkers = rememberAlertMarkers(true, selection, rules, coverage, thresholdKnots, repository)
    val markers = loadedMarkers.filter { it >= window.start && it < window.end }
    val alertTime = focus ?: window.start.plus(width.dividedBy(2))
    val accessible = alertTime.takeIf { it in coverage }?.let {
        plotSelection(series, selection, it, heightUnits, contacts, fromNotification = cursor == null && center != null)
    }
    val stops = remember(series, contacts, coverage) {
        (eventStops(series) + contacts.map { it to AlertTrigger.Eclipse })
            .filter { it.first in coverage }.sortedBy { it.first }
    }
    val previous = stops.lastOrNull { it.first < readingTime }
    val next = stops.firstOrNull { it.first > readingTime }
    val earlier = readingTime.minusSeconds(300).takeIf { it in coverage }
    val later = readingTime.plusSeconds(300).takeIf { it in coverage }
    val navigation = listOfNotNull(earlier, later).map { moment ->
        val label = stringResource(if (moment < readingTime) R.string.alert_plot_earlier else R.string.alert_plot_later)
        CustomAccessibilityAction(label) { parkedTime = moment; true }
    } + listOfNotNull(previous, next).map { (moment, trigger) ->
        val label = stringResource(
            if (moment < readingTime) R.string.alert_plot_previous else R.string.alert_plot_next,
            trigger.label(), selection.station.timeLabel(moment))
        CustomAccessibilityAction(label) { parkedTime = moment; true }
    }
    Column {
        val reading = when (series) {
            is PredictionSeries.Tide -> series.points.heightAt(readingTime)?.let {
                if (heightUnits == HeightUnits.IMPERIAL) "%.1f ft".format(displayHeight(it, heightUnits)) else "%.2f m".format(it)
            }
            is PredictionSeries.Current -> series.pinSpeedKnots(readingTime)?.let { "%.1f kn".format(it) }
            is PredictionSeries.Derived -> null
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            if (reading != null && readingTime in coverage) Text(reading, style = MaterialTheme.typography.labelLarge)
        }
        val description = stringResource(R.string.alert_plot_range, selection.station.timeLabel(start), selection.station.timeLabel(window.end))
        val marked = pluralStringResource(R.plurals.alert_plot_markers, markers.size, markers.size)
        Box(Modifier.fillMaxWidth().height(112.dp).clipToBounds().onSizeChanged { pixels = it.width }
            .semantics {
                contentDescription = "$title. $description. $marked"
                if (focus != null) stateDescription = listOfNotNull(selection.station.timeLabel(focus), reading).joinToString(", ")
            }
            .chartAlertPress(ready.capabilities.supports(PredictionPurpose.ALERT), { position ->
                val time = start.plusMillis((width.toMillis() * position.x.toDouble() / pixels.coerceAtLeast(1)).roundToLong())
                if (time in coverage) plotSelection(series, selection, time, heightUnits, contacts) else null
            }, accessible, navigation) { parkedTime = it.moment }
            .onKeyEvent { key ->
                if (key.type != KeyEventType.KeyDown) return@onKeyEvent false
                val moment = when (key.key) {
                    Key.DirectionLeft -> earlier
                    Key.DirectionRight -> later
                    else -> null
                } ?: return@onKeyEvent false
                parkedTime = moment
                true
            }.focusable()) {
            Canvas(Modifier.fillMaxSize()) {
                fun x(time: Instant) = size.width * (Duration.between(start, time).toMillis().toDouble() / width.toMillis()).toFloat()
                fun y(value: Double) = 12.dp.toPx() + (size.height - 24.dp.toPx()) * ((max - value) / range).toFloat()
                if (series is PredictionSeries.Derived) {
                    samples.forEach { drawCircle(ink, 3.dp.toPx(), Offset(x(it.first), size.height / 2)) }
                } else {
                    val path = Path()
                    samples.forEachIndexed { i, (time, value) ->
                        if (i == 0) path.moveTo(x(time), y(value)) else path.lineTo(x(time), y(value))
                    }
                    drawLine(quiet, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
                    drawPath(path, ink, style = Stroke(2.dp.toPx()))
                }
                readingTime.takeIf { it in window }?.let { drawLine(quiet, Offset(x(it), 0f), Offset(x(it), size.height), 1.dp.toPx()) }
            }
            AlertMarkerOverlay(markers, window, Modifier.matchParentSize())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
            Text(selection.station.timeLabel(start), style = MaterialTheme.typography.labelSmall)
            Text(selection.station.timeLabel(window.end), style = MaterialTheme.typography.labelSmall)
        }
        val visibleEvents = stops.filter { it.first in window }
        var showEvents by remember(selection.station.id) { mutableStateOf(false) }
        if (showEvents || visibleEvents.isNotEmpty()) {
            TextButton(onClick = { showEvents = !showEvents }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (showEvents) R.string.alert_hide_events else R.string.alert_show_events))
            }
            if (showEvents) {
                val locale = LocalConfiguration.current.locales[0]
                val formatter = remember(locale, selection.station.timezone) {
                    DateTimeFormatterBuilder().appendLocalized(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .appendLiteral(' ').appendZoneText(TextStyle.SHORT).toFormatter(locale)
                        .withZone(selection.station.plotZone())
                }
                EventList(visibleEvents, onSelect = { parkedTime = it.first }) { (moment, trigger) ->
                    val label = stringResource(R.string.alert_subject_time, trigger.label(), formatter.format(moment))
                    Text(when {
                        series is PredictionSeries.Tide && trigger is AlertTrigger.TideExtreme -> {
                            val height = series.events.first { it.time == moment }.heightMetres
                            stringResource(if (heightUnits == HeightUnits.IMPERIAL) R.string.query_tide_ft else R.string.query_tide_m,
                                label, displayHeight(height, heightUnits))
                        }
                        series is PredictionSeries.Current && trigger is AlertTrigger.CurrentPeak -> {
                            val speed = series.events.first { it.time == moment }.speedKnots
                            stringResource(R.string.alert_event_speed, label, abs(speed))
                        }
                        else -> label
                    })
                }
            }
        }
        AlertLine(parked ?: selection, parked != null, rules, now, onEdit)
        if (cursor != null) TextButton(onClick = { parkedTime = null }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (center == null) R.string.alert_plot_reset else R.string.alert_plot_reset_event))
        }
    }
}
