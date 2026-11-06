package io.openwaters.slackwater.android.detail

import io.openwaters.almanac.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.openwaters.slackwater.android.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.database.Station
import java.time.*
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun seasonalRatio(station: Station): Double? {
    if (!station.seasonalDominant) return null
    val terms = station.constituents
    val principal = terms.filter { it.name.uppercase() in setOf("M2", "S2", "K1", "O1") }.sumOf { it.amplitude }
    if (principal == 0.0) return Double.POSITIVE_INFINITY
    val annual = terms.firstOrNull { it.name.equals("SA", true) } ?: terms.firstOrNull { it.name == "SA_KV" }
    val seasonal = listOfNotNull(annual, terms.firstOrNull { it.name.equals("SSA", true) })
    return (0 until 720).maxOf { step ->
        abs(seasonal.sumOf { term ->
            val angle = step * .5 * (if (term.name.equals("SSA", true)) 2 else 1)
            val phase = term.phase + if (term.name.equals("SA_KV", true)) 283.4 else 0.0
            term.amplitude * cos(Math.toRadians(angle - phase))
        })
    } / principal
}

private data class MonthBand(val month: YearMonth, val min: Double, val max: Double)

@Composable
internal fun SummaryYear(
    station: StationId, repository: PredictionRepository, day: LocalDate, zone: ZoneId,
    tide: Boolean, rising: Boolean, locale: Locale, quantity: (Double) -> String,
) {
    val current = YearMonth.from(day)
    val months by key(station, current, tide, rising) {
        produceState<List<MonthBand>?>(null) {
            value = withContext(Dispatchers.Default) {
                (0L until 12L).mapNotNull { index ->
                    val month = current.plusMonths(index - 6)
                    val start = month.atDay(1).atStartOfDay(zone).toInstant()
                    val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
                    val series = (repository.load(station, TimeSpan(start, end), PredictionPurpose.DISPLAY) as? PredictionResult.Ready)?.series
                    val values = when (series) {
                        is PredictionSeries.Tide -> series.events.filter { it.time < end }.map { it.heightMetres }
                        is PredictionSeries.Current -> {
                            val direction = if (rising) CurrentEventKind.MAX_FLOOD else CurrentEventKind.MAX_EBB
                            series.events.filter { it.kind == direction && it.time < end }.map { abs(it.speedKnots) }
                        }
                        else -> emptyList()
                    }
                    if (values.isEmpty()) null else MonthBand(month, values.min(), values.max())
                }
            }
        }
    }
    val bands = months ?: return
    if (bands.size != 12) return
    Text(if (tide) stringResource(R.string.panel_year_tide) else stringResource(R.string.panel_year_current), style = MaterialTheme.typography.titleLarge)
    val turningPoints by produceState<List<SeasonEvent>>(emptyList(), current, zone) {
        value = withContext(Dispatchers.Default) {
            val from = current.minusMonths(12).atDay(1).atStartOfDay(zone).toInstant()
            val until = current.plusMonths(12).atDay(1).atStartOfDay(zone).toInstant()
            try { seasons(from, until) } catch (_: AlmanacOutOfRangeException) { emptyList() }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    val summary = bands.joinToString(". ") { "${it.month.month.getDisplayName(TextStyle.SHORT, locale)} · ${quantity(it.min)} – ${quantity(it.max)}" }
    Canvas(Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = summary }) {
        val floor = bands.minOf { it.min }
        val span = (bands.maxOf { it.max } - floor).coerceAtLeast(.0001)
        fun y(value: Double) = size.height * (1 - (value - floor) / span).toFloat()
        val step = size.width / 11
        val path = Path().apply {
            moveTo(0f, y(bands.first().max))
            bands.forEachIndexed { index, band -> lineTo(index * step, y(band.max)) }
            for (index in bands.indices.reversed()) lineTo(index * step, y(bands[index].min))
            close()
        }
        drawPath(path, color.copy(alpha = .55f))
        for (turn in turningPoints) {
            val date = turn.time.atZone(zone).toLocalDate()
            val index = bands.indexOfFirst { it.month == YearMonth.from(date) }
            if (index < 0) continue
            val x = ((index + (date.dayOfMonth - .5f) / date.lengthOfMonth() - .5f) * step).coerceIn(0f, size.width)
            drawLine(color.copy(alpha = .5f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        }
        drawLine(color, Offset(6 * step, 0f), Offset(6 * step, size.height), 2.dp.toPx())
    }
    val largest = bands.sortedByDescending { if (tide) it.max - it.min else it.max }.take(2)
    val month = largest.first().month.month.getDisplayName(TextStyle.FULL, locale)
    val distances = largest.mapNotNull { band ->
        val mid = band.month.atDay((band.month.lengthOfMonth() + 1) / 2).atTime(12, 0).atZone(zone).toInstant()
        val solstice = turningPoints.filter { it.kind == SeasonKind.JUNE_SOLSTICE || it.kind == SeasonKind.DECEMBER_SOLSTICE }
            .minOfOrNull { Duration.between(mid, it.time).abs().seconds } ?: return@mapNotNull null
        val equinox = turningPoints.filter { it.kind == SeasonKind.MARCH_EQUINOX || it.kind == SeasonKind.SEPTEMBER_EQUINOX }
            .minOfOrNull { Duration.between(mid, it.time).abs().seconds } ?: return@mapNotNull null
        solstice - equinox
    }
    val decisive = distances.size == 2 && abs(distances.sum()) > Duration.ofDays(50).seconds
    val caption = when {
        decisive && distances.all { it < 0 } -> if (tide) R.string.panel_widest_solstice else R.string.panel_hardest_solstice
        decisive && distances.all { it > 0 } -> if (tide) R.string.panel_widest_equinox else R.string.panel_hardest_equinox
        else -> if (tide) R.string.panel_widest_month else R.string.panel_hardest_month
    }
    Text(stringResource(caption, month))
}
