package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openwaters.almanac.*
import io.openwaters.slackwater.android.R
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LunarWeekmarks(dates: List<LocalDate>, peaks: Map<LocalDate, WaterMark>, zone: ZoneId, locale: Locale) {
    if (dates.isEmpty()) return
    val first = dates.first().atStartOfDay(zone).toInstant()
    val end = dates.last().plusDays(1).atStartOfDay(zone).toInstant()
    val phases by produceState<List<MoonPhaseEvent>>(emptyList(), first, end) {
        value = withContext(Dispatchers.Default) {
            try { searchMoonPhases(first.minus(Duration.ofDays(8)), end.plus(Duration.ofDays(8))) }
            catch (_: AlmanacOutOfRangeException) { emptyList() }
        }
    }
    val events = phases.associateBy { it.time.atZone(zone).toLocalDate() }
    val names = androidx.compose.ui.res.stringArrayResource(R.array.panel_phases)
    Row(Modifier.fillMaxWidth()) {
        for (date in dates) {
            val phase = events[date]?.phase
            val index = when (phase) {
                MoonPhaseName.NEW -> 0
                MoonPhaseName.FIRST_QUARTER -> 2
                MoonPhaseName.FULL -> 4
                MoonPhaseName.LAST_QUARTER -> 6
                null -> null
            }
            if (index == null) Spacer(Modifier.weight(1f))
            else {
                val label = names[index] + " · " + DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
                Text(listOf("○", "◐", "●", "◑")[index / 2], Modifier.weight(1f).semantics { contentDescription = label },
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    val springAndNeap = peaks.entries.groupBy { (date, _) ->
        val noon = date.atTime(12, 0).atZone(zone).toInstant()
        val nearest = phases.minByOrNull { Duration.between(it.time, noon).abs() }?.phase
        nearest == MoonPhaseName.NEW || nearest == MoonPhaseName.FULL
    }
    val spring = springAndNeap[true]?.map { it.value.value }?.average()
    val neap = springAndNeap[false]?.map { it.value.value }?.average()
    if (spring != null && neap != null && spring >= 1.05 * neap) Text(stringResource(R.string.panel_springs_follow))
}

@Composable
internal fun LevelFigure(levels: List<Pair<String, Double>>, quantity: (Double) -> String) {
    val high = levels.maxOf { it.second }
    val low = levels.minOf { it.second }
    val span = (high - low).coerceAtLeast(.001)
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val top = 6.dp.toPx()
        fun y(level: Double) = top + ((high - level) / span).toFloat() * (size.height - top * 2)
        val placed = mutableListOf<Float>()
        val ordered = levels.sortedByDescending { it.second }
        for ((index, pair) in ordered.withIndex()) {
            val y = y(pair.second)
            if (placed.any { abs(it - y) < 4.dp.toPx() }) continue
            val x = size.width * if (index == 0 || index == ordered.lastIndex) .10f else .22f
            drawLine(ink, Offset(x, y), Offset(size.width * .92f, y), 1.dp.toPx())
            placed.add(y)
        }
        drawLine(ink, Offset(size.width * .11f, y(high)), Offset(size.width * .11f, y(low)), 1.dp.toPx())
    }
    levels.sortedByDescending { it.second }.forEach { (label, value) -> Text("$label · ${quantity(value)}") }
}

@Composable
internal fun OrbitFigure(moon: Boolean, kilometres: Long, closest: Double?, farthest: Double?, outbound: Boolean) {
    if (closest == null || farthest == null || farthest <= closest) return
    val fraction = ((kilometres - closest) / (farthest - closest)).coerceIn(0.0, 1.0)
    val ink = MaterialTheme.colorScheme.primary
    val warm = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val a = size.width * .40f
        val b = size.height * .40f
        drawOval(ink, Offset(center.x - a, center.y - b), Size(a * 2, b * 2), style = Stroke(1.dp.toPx()))
        drawCircle(warm, if (moon) 5.dp.toPx() else 7.dp.toPx(), Offset(center.x + a * .32f, center.y))
        val angle = acos(1 - 2 * fraction) * if (outbound) 1 else -1
        drawCircle(ink, 4.dp.toPx(), Offset(center.x + a * cos(angle).toFloat(), center.y - b * sin(angle).toFloat()))
    }
    Text(stringResource(R.string.panel_orbit_scale), style = MaterialTheme.typography.bodySmall)
}
