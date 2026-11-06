package io.openwaters.slackwater.android.alerts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.data.HeightUnits
import io.openwaters.slackwater.android.data.ReadingPreferences
import java.time.Duration
import java.time.Instant
import kotlin.math.*

private const val METRES_PER_FOOT = 0.3048

private fun displayHeight(metres: Double, units: HeightUnits) =
    if (units == HeightUnits.IMPERIAL) metres / METRES_PER_FOOT else metres

internal fun plotSelection(
    series: PredictionSeries, offer: ChartAlertSelection, time: Instant, units: HeightUnits,
    eclipseContacts: List<Instant> = emptyList(),
): ChartAlertSelection? {
    val stops = when (series) {
        is PredictionSeries.Tide -> series.events.map { it.time to AlertTrigger.TideExtreme(it.kind == ExtremeKind.HIGH) }
        is PredictionSeries.Derived -> series.slacks.map { it.time to AlertTrigger.Slack }
        is PredictionSeries.Current -> series.events.map { it.time to when (it.kind) {
            CurrentEventKind.SLACK -> AlertTrigger.SlackWindowOpens
            CurrentEventKind.MAX_FLOOD -> AlertTrigger.CurrentPeak(true)
            CurrentEventKind.MAX_EBB -> AlertTrigger.CurrentPeak(false)
        } }
    }
    val nearest = stops.minByOrNull { Duration.between(it.first, time).abs() }
    if (nearest != null && Duration.between(nearest.first, time).abs() <= Duration.ofMinutes(15)) {
        return offer.copy(moment = nearest.first, trigger = nearest.second)
    }
    val contact = eclipseContacts.firstOrNull { Duration.between(it, time).abs() <= Duration.ofSeconds(1) }
    if (contact != null) return offer.copy(moment = contact, trigger = AlertTrigger.Eclipse)
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
    val parked = parkedTime?.takeIf { it in coverage }?.let { plotSelection(series, selection, it, heightUnits, contacts) }
    val focus = parked?.moment ?: center
    val width = minOf(Duration.ofHours(12), Duration.between(coverage.start, coverage.end))
    if (width.toMillis() <= 0) return
    val start = focus?.minus(width.dividedBy(2)) ?: coverage.start
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
    val markers = rememberAlertMarkers(true, selection, rules, window, thresholdKnots, repository)
    val midpoint = window.start.plus(width.dividedBy(2))
    val accessible = midpoint.takeIf { it in coverage }?.let { plotSelection(series, selection, it, heightUnits, contacts) }
    Column {
        val readingTime = parked?.moment ?: center ?: now
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
            .semantics { contentDescription = "$title. $description. $marked" }
            .chartAlertPress(ready.capabilities.supports(PredictionPurpose.ALERT), { position ->
                val time = start.plusMillis((width.toMillis() * position.x.toDouble() / pixels.coerceAtLeast(1)).roundToLong())
                if (time in coverage) plotSelection(series, selection, time, heightUnits, contacts) else null
            }, accessible) { parkedTime = it.moment }) {
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
                focus?.let { drawLine(quiet, Offset(x(it), 0f), Offset(x(it), size.height), 1.dp.toPx()) }
            }
            AlertMarkerOverlay(markers, window, Modifier.matchParentSize())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(selection.station.timeLabel(start), style = MaterialTheme.typography.labelSmall)
            Text(selection.station.timeLabel(window.end), style = MaterialTheme.typography.labelSmall)
        }
        AlertLine(parked ?: selection, parked != null, rules, now, onEdit)
        if (parked != null) TextButton(onClick = { parkedTime = null }) {
            Text(stringResource(if (center == null) R.string.alert_plot_reset else R.string.alert_plot_reset_event))
        }
    }
}
