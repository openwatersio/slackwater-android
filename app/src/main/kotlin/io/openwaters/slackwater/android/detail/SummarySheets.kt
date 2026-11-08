package io.openwaters.slackwater.android.detail

import io.openwaters.slackwater.android.StationDialogContent
import io.openwaters.slackwater.android.rememberStationDialog
import io.openwaters.slackwater.android.core.tour.TourStep
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.almanac.*
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.ExtremeKind
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.core.timeline.TimelineMode
import io.openwaters.slackwater.android.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WaterMark(val time: Instant, val value: Double, val rising: Boolean, val start: Instant? = null, val level: Double? = null)

internal fun PredictionSeries.waterMarks(): List<WaterMark> = when (this) {
    is PredictionSeries.Tide -> buildList {
        var previous = events.firstOrNull() ?: return@buildList
        for (current in events.drop(1)) {
            if (current.kind == previous.kind) {
                if ((current.kind == ExtremeKind.LOW) == (current.heightMetres < previous.heightMetres)) previous = current
                continue
            }
            val rising = current.kind == ExtremeKind.HIGH
            val height = if (rising) current.heightMetres - previous.heightMetres else previous.heightMetres - current.heightMetres
            if (height > 0) add(WaterMark(current.time, height, rising, previous.time, current.heightMetres))
            previous = current
        }
    }
    is PredictionSeries.Current -> events.filter { it.kind != CurrentEventKind.SLACK }.map {
        WaterMark(it.time, abs(it.speedKnots), it.kind == CurrentEventKind.MAX_FLOOD)
    }
    is PredictionSeries.Derived -> emptyList()
}

private enum class Sheet { WATER, MOON, SUN }

@Composable
internal fun rememberDetailMoon(selected: Instant): MoonIllumination? =
    remember(selected.epochSecond / 60) {
        try { moonIllumination(selected) } catch (_: AlmanacOutOfRangeException) { null }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailSummary(
    station: StationIdentity, timeline: DetailTimeline, preferences: ReadingPreferences, repository: PredictionRepository,
    moon: MoonIllumination?, referencePortName: String? = null, provisional: Boolean = false,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = timeline.zone
    val selected = timeline.state.selected
    val day = selected.atZone(zone).toLocalDate()
    val clock = remember(locale, zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone) }
    val stamp = remember(locale, zone) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone) }
    val series = timeline.series
    val tide = series is PredictionSeries.Tide
    val marks = remember(series) { series.waterMarks() }
    val mark = marks.firstOrNull { it.time > selected }?.takeIf { it.start == null || selected >= it.start }
    val direction = stringResource(if (mark?.rising == true) R.string.panel_flood else R.string.panel_ebb)
    val imperial = preferences.heightUnits(locale) == HeightUnits.IMPERIAL
    val unit = stringResource(if (tide) {
        if (imperial) R.string.unit_feet else R.string.unit_metres
    } else when (preferences.speedUnit) {
        SpeedUnit.KNOTS -> R.string.unit_knots
        SpeedUnit.KILOMETRES_PER_HOUR -> R.string.unit_kilometres_per_hour
        SpeedUnit.METRES_PER_SECOND -> R.string.unit_metres_per_second
    })
    fun quantity(value: Double): String {
        val number = if (tide) formatHeightValue(value, imperial, locale)
            else formatSpeedValue(value, SpeedUnitKind.valueOf(preferences.speedUnit.name), locale)
        return "$number $unit"
    }
    val ranking by key(timeline, day) {
        produceState(emptyList<WaterMark>()) {
            if (timeline.mode != TimelineMode.CONTINUOUS || series is PredictionSeries.Derived) return@produceState
            val start = day.minusDays(15).atStartOfDay(zone).toInstant()
            val end = day.plusDays(15).atStartOfDay(zone).toInstant()
            value = withContext(Dispatchers.Default) {
                try {
                    val ready = repository.load(station.id, TimeSpan(start.minusSeconds(86400), end), PredictionPurpose.DISPLAY)
                        as? PredictionResult.Ready
                    ready?.series?.waterMarks().orEmpty().filter { it.time >= start && it.time < end }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            }
        }
    }
    val comparison = remember(ranking, tide, mark?.rising) { ranking.filter { tide || it.rising == mark?.rising } }
    // Samples independently rebuilt for the ranking may differ in the last decimal place.
    val higher = remember(comparison, mark) { comparison.count { mark != null && it.value > mark.value + 0.0001 } }
    val marked = comparison.size >= 2 && higher * 10 <= comparison.size
    val stationFacts by produceState<Pair<Double?, io.openwaters.slackwater.database.Station.AstronomicalBounds?>?>(null, station.id) {
        if (station.kind == StationKind.Tide) value = withContext(Dispatchers.IO) {
            BundledTideDatabase(context.assets).station(station.id)?.let { record -> seasonalRatio(record) to record.astronomicalBounds }
        }
    }
    val season = stationFacts?.first
    val waterTitle = stringResource(when (series) {
        is PredictionSeries.Derived -> R.string.panel_shape
        is PredictionSeries.Tide -> R.string.panel_range
        else -> R.string.panel_max
    })
    val waterValue = if (series is PredictionSeries.Derived) stringResource(R.string.panel_no_speed)
        else mark?.let { (if (provisional && !tide) "~" else "") + quantity(it.value) }
    val caption = when {
        series is PredictionSeries.Derived -> referencePortName?.let { stringResource(R.string.panel_port, it) } ?: stringResource(R.string.panel_reference)
        mark == null -> ""
        season != null -> if (season >= 3) stringResource(R.string.panel_mostly) else stringResource(R.string.panel_partly)
        tide && marked -> stringResource(if (higher == 0) R.string.panel_biggest else R.string.panel_marked)
        tide -> stringResource(if (mark.rising) R.string.panel_low_high else R.string.panel_high_low)
        marked -> stringResource(if (higher == 0) R.string.panel_hardest else R.string.panel_strong, direction.lowercase(locale), clock.format(mark.time))
        else -> stringResource(R.string.panel_at, direction, clock.format(mark.time))
    }
    var sheet by rememberStationDialog<Sheet?>(null, station.id.value)
    val phases = stringArrayResource(R.array.panel_phases)
    val phase = moon?.let { phaseIndex(it.phase) }
    val observer = remember(station) { Observer(station.latitude, station.longitude) }
    val sky = rememberMoonContext(day, zone, observer)
    val lunar = sky.lunar.firstOrNull { selected >= it.p1 && selected <= it.p4 }
    val eclipse = when {
        sky.solar.any { selected >= it.c1 && selected <= it.c4 } -> stringResource(R.string.panel_solar_eclipse)
        lunar?.kind == LunarEclipseKind.TOTAL -> stringResource(R.string.panel_total_eclipse)
        lunar?.kind == LunarEclipseKind.PARTIAL -> stringResource(R.string.panel_partial_eclipse)
        lunar != null -> stringResource(R.string.panel_penumbra)
        else -> null
    }
    val moonName = eclipse ?: phase?.let(phases::get).orEmpty()
    val lit = stringResource(R.string.panel_lit, ((moon?.fraction ?: 0.0) * 100).roundToInt())
    val ordinaryTide = when (phase) {
        0, 4 -> stringResource(R.string.panel_spring)
        2, 6 -> stringResource(R.string.panel_neap)
        else -> lit
    }
    val apsis = sky.apsides.minByOrNull { Duration.between(it.time, selected).abs() }
        ?.takeIf { Duration.between(it.time, selected).abs() <= Duration.ofDays(2) }
    val moonCaption = if (apsis == null) ordinaryTide else {
        val perigee = apsis.kind == MoonApsisKind.PERIGEE
        stringResource(when (phase) {
            0, 4 -> if (perigee) R.string.panel_perigean_spring else R.string.panel_apogean_spring
            2, 6 -> if (perigee) R.string.panel_perigean_neap else R.string.panel_apogean_neap
            else -> if (perigee) R.string.panel_perigean else R.string.panel_apogean
        })
    }
    val solar = remember(timeline.days) {
        timeline.days.flatMap { listOfNotNull(it.sunrise?.let { t -> t to R.string.panel_rise }, it.sunset?.let { t -> t to R.string.panel_set }) }.sortedBy { it.first }
    }
    val next = solar.firstOrNull { it.first > selected }
    val metadata = timeline.days.firstOrNull { it.date == day }
    val duration = metadata?.sunrise?.let { rise -> metadata.sunset?.let { set ->
        if (rise <= set) Duration.between(rise, set).toMinutes()
        else Duration.between(day.atStartOfDay(zone).toInstant(), set)
            .plus(Duration.between(rise, day.plusDays(1).atStartOfDay(zone).toInstant())).toMinutes()
    } }
    val sunValue = next?.let { stringResource(R.string.panel_at, stringResource(it.second), clock.format(it.first)) } ?: "—"
    val sunCaption = duration?.let { stringResource(R.string.panel_daylight, it / 60, it % 60) } ?: "—"
    val dayPeaks = remember(comparison) { comparison.groupBy { it.time.atZone(zone).toLocalDate() }.mapValues { it.value.maxBy(WaterMark::value) } }
    val coverage = timeline.state.coverage
    val weekPeaks = remember(marks, zone, coverage) {
        marks.groupBy { it.time.atZone(zone).toLocalDate() }.filterKeys {
            it.atStartOfDay(zone).toInstant() >= coverage.start && it.plusDays(1).atStartOfDay(zone).toInstant() <= coverage.end
        }.mapValues { it.value.maxBy(WaterMark::value) }
    }
    var barStart by rememberSaveable(station.id.value, timeline.state.anchor.toEpochDay()) { mutableLongStateOf(timeline.state.anchor.toEpochDay()) }
    if (!timeline.state.busy) LaunchedEffect(day, barStart) {
        if (day.toEpochDay() - barStart >= 5) barStart = day.toEpochDay()
        else if (day.toEpochDay() < barStart) barStart = day.minusDays(4).toEpochDay()
    }
    val water: @Composable (Modifier) -> Unit = { modifier ->
        if (waterValue != null) SummaryTile(waterTitle, waterValue, caption, modifier,
            if (season != null || comparison.isNotEmpty() && (tide || marked)) ({ sheet = Sheet.WATER }) else null,
            beside = tide, approximate = provisional && !tide,
            glyph = when { season != null -> "▦"; marked -> "↕"; else -> null }) {
            if (tide) PeakBars((0L..6L).map { LocalDate.ofEpochDay(barStart + it) }, weekPeaks, day, locale, ::quantity)
        }
    }
    val moonCard: @Composable (Modifier) -> Unit = { modifier ->
        if (moon != null) SummaryTile(stringResource(R.string.panel_moon), moonName, moonCaption, modifier.tourAnchor(TourStep.MOON_CARD), { sheet = Sheet.MOON }) {
            MoonDisc(selected, observer, moon, eclipse)
        }
    }
    val sunCard: @Composable (Modifier) -> Unit = { modifier ->
        SummaryTile(stringResource(R.string.panel_sun), sunValue, sunCaption, modifier, { sheet = Sheet.SUN })
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 700.dp && LocalConfiguration.current.fontScale < 1.5f) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { water(Modifier.weight(2f)); moonCard(Modifier.weight(1f)); sunCard(Modifier.weight(1f)) }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            water(Modifier.fillMaxWidth())
            if (LocalConfiguration.current.fontScale >= 1.5f) { moonCard(Modifier.fillMaxWidth()); sunCard(Modifier.fillMaxWidth()) }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { moonCard(Modifier.weight(1f)); sunCard(Modifier.weight(1f)) }
        }
    }
    val open = sheet ?: return
    fun jump(time: Instant) { sheet = null; timeline.jump(time) }
    StationDialogContent {
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (open) { Sheet.WATER -> waterTitle; Sheet.MOON -> stringResource(R.string.panel_moon); Sheet.SUN -> stringResource(R.string.panel_sun) },
                        Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { sheet = null }) { Text(stringResource(android.R.string.ok)) }
                }
                if (open == Sheet.WATER && mark != null) {
                    Text(quantity(mark.value), style = MaterialTheme.typography.headlineMedium)
                    Text(if (tide) stringResource(R.string.panel_swing_explain, stringResource(if (mark.rising) R.string.panel_low_high else R.string.panel_high_low))
                        else stringResource(R.string.panel_max_explain, station.name))
                    Text(stringResource(R.string.panel_rank_title), style = MaterialTheme.typography.titleLarge)
                    Text(if (tide) stringResource(R.string.panel_biggest_day) else stringResource(R.string.panel_max_day, direction.lowercase(locale)))
                    val days = (-15L until 15L).map { day.plusDays(it) }
                    PeakBars(days, dayPeaks, day, locale, ::quantity, ::jump)
                    LunarWeekmarks(days, dayPeaks, zone, locale)
                    val bigger = comparison.firstOrNull { it.time > mark.time && it.value > mark.value + 0.0001 }
                    val rankingText = if (higher == 0) {
                        if (tide) stringResource(R.string.panel_biggest_swing) else stringResource(R.string.panel_hardest_max, direction.lowercase(locale))
                    } else stringResource(if (tide) R.string.panel_swing_rank else R.string.panel_max_rank, higher, comparison.size)
                    Text(rankingText,
                        if (bigger == null) Modifier else Modifier.clickable { jump(bigger.time) })
                    if (series is PredictionSeries.Current) {
                        val threshold = preferences.effectiveThresholdKn(station.id)
                        val slack = remember(series, mark, threshold) { summarySlack(series, mark.time, threshold) }
                        if (slack != null) Text(stringResource(R.string.panel_slack_compared, slack.first, slack.second))
                    }
                    stationFacts?.second?.let { bounds ->
                        Text(stringResource(R.string.panel_ends), style = MaterialTheme.typography.titleLarge)
                        val levels = buildList {
                            add(stringResource(R.string.panel_hat_label) to bounds.hat)
                            add(stringResource(R.string.panel_lat_label) to bounds.lat)
                            comparison.mapNotNull { it.level }.maxOrNull()?.let { add(stringResource(R.string.panel_high_label) to it) }
                            comparison.mapNotNull { it.level }.minOrNull()?.let { add(stringResource(R.string.panel_low_label) to it) }
                            mark.level?.let { add(stringResource(R.string.panel_turn_label) to it) }
                        }
                        LevelFigure(levels, ::quantity)
                    }
                    SummaryYear(station.id, repository, day, zone, tide, mark.rising, locale, ::quantity)
                    season?.let { ratio ->
                        Text(when {
                            ratio.isInfinite() -> stringResource(R.string.panel_season_large)
                            ratio.roundToInt() == 1 -> stringResource(R.string.panel_season_equal)
                            else -> stringResource(R.string.panel_ratio, if (ratio >= 10) (ratio / 10).roundToInt() * 10.0 else ratio)
                        })
                        Text(stringResource(if (ratio >= 3) R.string.panel_season_river else R.string.panel_season_tide, station.name))
                    }
                } else if (open != Sheet.WATER) {
                    Text(if (open == Sheet.MOON) moonName else sunValue, style = MaterialTheme.typography.headlineMedium)
                    if (open == Sheet.MOON && moon != null) MoonDisc(selected, observer, moon, eclipse)
                    Text(if (open == Sheet.MOON) listOf(lit, moonCaption).distinct().joinToString(" · ") else sunCaption)
                    if (open == Sheet.MOON && phase != null) {
                        Text(stringArrayResource(R.array.panel_phase_explanation)[phase])
                        when (phase) {
                            0, 4 -> Text(stringResource(R.string.panel_spring_explain))
                            2, 6 -> Text(stringResource(R.string.panel_neap_explain))
                        }
                        apsis?.let { Text(stringResource(if (it.kind == MoonApsisKind.PERIGEE) R.string.panel_perigee_explain else R.string.panel_apogee_explain)) }
                    }
                    if (open == Sheet.SUN) {
                        listOfNotNull(solar.lastOrNull { it.first < selected }, next).forEach { (time, label) ->
                            TextButton(onClick = { jump(time) }) { Text(stringResource(label) + " · " + stamp.format(time)) }
                        }
                    }
                    SummarySkyFacts(open == Sheet.MOON, selected, station, zone, stamp, ::jump)
                }
            }
        }
    }

}

internal fun phaseIndex(phase: Double): Int {
    val age = phase * 29.530588
    return when {
        age <= 1.7 || age >= 29.530588 - 1.7 -> 0
        abs(age - 29.530588 / 4) <= 1.4 -> 2
        abs(age - 29.530588 / 2) <= 1.7 -> 4
        abs(age - 3 * 29.530588 / 4) <= 1.4 -> 6
        phase < .25 -> 1
        phase < .5 -> 3
        phase < .75 -> 5
        else -> 7
    }
}

@Composable
private fun SummaryTile(title: String, value: String, caption: String, modifier: Modifier, open: (() -> Unit)?, beside: Boolean = false, approximate: Boolean = false, glyph: String? = null, extra: @Composable () -> Unit = {}) {
    Card(modifier.then(if (open != null) Modifier.clickable(onClick = open) else Modifier)) {
        val reading: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                glyph?.let { Text(it, Modifier.clearAndSetSemantics { }) }
                Text(title, style = MaterialTheme.typography.labelLarge)
                if (open != null) Text("›", Modifier.clearAndSetSemantics { })
            }
            Text(value, style = MaterialTheme.typography.titleLarge,
                color = if (approximate) androidx.compose.ui.graphics.Color(0xFFE8A33D) else LocalContentColor.current)
            Text(caption, style = MaterialTheme.typography.bodySmall)
        }
        if (beside && LocalConfiguration.current.fontScale < 1.5f) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { reading() }
                Box(Modifier.weight(1.2f)) { extra() }
            }
        } else Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            reading()
            extra()
        }
    }
}

@Composable
private fun PeakBars(dates: List<LocalDate>, peaks: Map<LocalDate, WaterMark>, selected: LocalDate, locale: Locale,
                     quantity: (Double) -> String, onClick: ((Instant) -> Unit)? = null) {
    val max = dates.mapNotNull { peaks[it]?.value }.maxOrNull()?.takeIf { it > 0 } ?: return
    var inspecting by remember(dates, selected) { mutableStateOf<LocalDate?>(null) }
    val actionable = dates.filter { peaks[it] != null }
    val selection = inspecting ?: selected.takeIf { it in actionable } ?: actionable.first()
    val currentJump by rememberUpdatedState(onClick)
    val haptic = LocalHapticFeedback.current
    val currentPeak = peaks.getValue(selection)
    if (onClick != null && inspecting != null) Text("$selection · ${quantity(currentPeak.value)}")
    val controls = if (onClick == null) Modifier else Modifier.clearAndSetSemantics {
        stateDescription = "$selection · ${quantity(currentPeak.value)}"
        progressBarRangeInfo = ProgressBarRangeInfo(actionable.indexOf(selection).toFloat(), 0f..actionable.lastIndex.toFloat(),
            (actionable.size - 2).coerceAtLeast(0))
        setProgress { index ->
            inspecting = actionable[index.roundToInt().coerceIn(actionable.indices)]
            true
        }
        onClick { currentJump?.invoke(currentPeak.time); true }
    }.pointerInput(dates, peaks) {
        fun dateAt(x: Float): LocalDate? = dates[(x * dates.size / size.width).toInt().coerceIn(dates.indices)].takeIf { it in peaks }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val interrupted = withTimeoutOrNull(250L) {
                while (true) {
                    val change = awaitPointerEvent().changes.first()
                    if (change.isConsumed || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (interrupted != null) return@awaitEachGesture
            inspecting = dateAt(down.position.x)
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.first()
                    val delta = change.position - down.position
                    if (change.isConsumed || abs(delta.y) > maxOf(20.dp.toPx(), abs(delta.x))) break
                    val date = dateAt(change.position.x)
                    if (date != inspecting) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    inspecting = date
                    change.consume()
                    if (!change.pressed) {
                        date?.let(peaks::get)?.let { currentJump?.invoke(it.time) }
                        break
                    }
                }
            } finally { inspecting = null }
        }
    }
    Row(Modifier.fillMaxWidth().height(46.dp).then(controls), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (date in dates) {
            val peak = peaks[date]
            if (peak == null) Spacer(Modifier.weight(1f)) else {
                val label = "${date} · ${quantity(peak.value)}"
                Box(Modifier.weight(1f).height((16 + 30 * peak.value / max).dp)
                    .background(if (date == (inspecting ?: selected)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                    .semantics { contentDescription = label },
                    contentAlignment = Alignment.BottomCenter) {
                    if (dates.size <= 7) Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun summarySlack(series: PredictionSeries.Current, maximum: Instant, threshold: Double): Pair<Int, Int>? {
    val runs = series.windows(threshold, Duration.ofMinutes(10)).filter { it.duration != null }
    if (runs.size < 3) return null
    val durations = runs.map { requireNotNull(it.duration).toMillis() / 60000.0 }.sorted()
    val median = durations[durations.size / 2]
    val near = runs.minBy { window -> window.slacks.minOf { Duration.between(maximum, it).abs() } }
    val minutes = requireNotNull(near.duration).toMillis() / 60000.0
    if (abs(minutes - median) <= median * .1 || minutes.roundToInt() == median.roundToInt()) return null
    return minutes.roundToInt() to median.roundToInt()
}
