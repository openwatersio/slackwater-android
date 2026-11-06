package io.openwaters.slackwater.android.shortcuts

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.EventList
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.shortcuts.QueryAnswer
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.data.HeightUnits
import io.openwaters.slackwater.android.data.ReadingPreferences
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun QueryAnswerView(station: StationIdentity, answer: QueryAnswer, prefs: ReadingPreferences, request: ShortcutRequest,
                             queriedAt: Instant) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale, station.timezone) {
        DateTimeFormatterBuilder().appendLocalized(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .appendLiteral(' ').appendZoneText(TextStyle.SHORT).toFormatter(locale)
            .withZone(station.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC)
    }
    Text(station.name, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    if (station.region.isNotBlank()) Text(station.region, style = MaterialTheme.typography.bodySmall)
    when (answer) {
        QueryAnswer.Unavailable -> Text(stringResource(R.string.query_unavailable))
        QueryAnswer.Off -> Text(stringResource(R.string.query_off))
        QueryAnswer.None -> {
            val date = request.date
            Text(if (date != null) stringResource(R.string.query_none_date,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date))
            else stringResource(if (request.kind == ShortcutKind.SLACK) R.string.query_none_window else R.string.query_none_tide))
        }
        is QueryAnswer.Tides -> {
            val imperial = prefs.heightUnits(locale) == HeightUnits.IMPERIAL
            if (request.date == null) Text(stringResource(R.string.query_tide_wait,
                waitLabel(queriedAt, answer.events.first().time, locale)))
            EventList(answer.events) { event ->
                val height = if (imperial) event.heightMetres / 0.3048 else event.heightMetres
                Text(stringResource(if (imperial) R.string.query_tide_ft else R.string.query_tide_m,
                    formatter.format(event.time), height))
            }
            AnswerCurve(remember(answer) { answer.curve.points.map { it.time to it.heightMetres } },
                answer.events.map { it.time to it.time }, formatter,
                stringResource(R.string.alert_plot_tide, if (imperial) "ft" else "m"))
        }
        is QueryAnswer.Windows -> {
            EventList(answer.windows) { window ->
                val start = window.start
                val end = window.end
                if (request.date == null) {
                    Text(when {
                        start == null || start <= queriedAt -> if (end == null) stringResource(R.string.query_open)
                            else stringResource(R.string.query_remaining, waitLabel(queriedAt, end, locale))
                        end == null -> stringResource(R.string.query_opens, waitLabel(queriedAt, start, locale))
                        else -> stringResource(R.string.query_opens_duration, waitLabel(queriedAt, start, locale), waitLabel(start, end, locale))
                    })
                }
                Text(when {
                    start != null && end != null -> stringResource(R.string.query_window, formatter.format(start), formatter.format(end))
                    start != null -> stringResource(R.string.query_window_from, formatter.format(start))
                    end != null -> stringResource(R.string.query_window_until, formatter.format(end))
                    else -> stringResource(R.string.query_window_at, formatter.format(window.highlight))
                })
            }
            AnswerCurve(remember(answer) { answer.curve.points.map { it.time to it.speedKnots } },
                answer.windows.map {
                    if (it.start != null && it.end != null) requireNotNull(it.start) to requireNotNull(it.end)
                    else it.highlight to it.highlight
                }, formatter, stringResource(R.string.alert_plot_current), prefs.effectiveThresholdKn(station.id))
        }
    }
}

private fun waitLabel(start: Instant, end: Instant, locale: Locale): String {
    val seconds = Duration.between(start, end).seconds
    val measure = if (seconds >= 3600) Measure((seconds / 3600.0).roundToInt(), MeasureUnit.HOUR)
        else Measure((seconds / 60.0).roundToInt().coerceIn(1, 59), MeasureUnit.MINUTE)
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).formatMeasures(measure)
}

@Composable
private fun AnswerCurve(samples: List<Pair<Instant, Double>>, highlights: List<Pair<Instant, Instant>>, formatter: DateTimeFormatter,
                        description: String, threshold: Double? = null) {
    if (samples.size < 2) return
    val start = samples.first().first.toEpochMilli()
    val span = samples.last().first.toEpochMilli() - start
    if (span <= 0) return
    val low = minOf(-(threshold ?: 0.0), samples.minOf { it.second })
    val high = maxOf(threshold ?: 0.0, samples.maxOf { it.second })
    val range = (high - low).takeIf { it > 0 } ?: 1.0
    val ink = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(104.dp), contentDescription = description) {
        fun x(time: Instant) = size.width * ((time.toEpochMilli() - start).toDouble() / span).toFloat()
        fun y(value: Double) = 4.dp.toPx() + (size.height - 8.dp.toPx()) * ((high - value) / range).toFloat()
        drawLine(axis, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
        threshold?.let {
            for (limit in listOf(-it, it)) drawLine(axis, Offset(0f, y(limit)), Offset(size.width, y(limit)), 1.dp.toPx())
        }
        val path = Path()
        samples.forEachIndexed { i, (time, value) ->
            if (i == 0) path.moveTo(x(time), y(value)) else path.lineTo(x(time), y(value))
        }
        drawPath(path, ink, style = Stroke(2.dp.toPx()))
        for ((from, until) in highlights) {
            if (from == until) drawLine(marker, Offset(x(from), 0f), Offset(x(from), size.height), 2.dp.toPx())
            else drawRect(marker.copy(alpha = 0.20f), Offset(x(from), 0f), Size(x(until) - x(from), size.height))
        }
    }
    Text(stringResource(R.string.query_curve_extent, formatter.format(samples.first().first), formatter.format(samples.last().first)),
        style = MaterialTheme.typography.bodySmall)
}
