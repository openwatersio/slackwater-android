package io.openwaters.slackwater.android.detail

import io.openwaters.slackwater.android.core.tour.TourStep
import android.text.format.DateFormat
import android.content.res.Resources
import io.openwaters.slackwater.android.R
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.*
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.conflate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.openwaters.slackwater.android.core.prediction.ChartModel
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.core.timeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
internal fun TimelineCanvas(
    station: StationIdentity,
    series: PredictionSeries,
    days: List<TimelineDay>,
    selected: Instant,
    now: Instant,
    modifier: Modifier = Modifier,
    thresholdKnots: Double = 0.5,
    alertMoments: List<Instant> = emptyList(),
    useFeet: Boolean = false,
    speedUnit: SpeedUnitKind = SpeedUnitKind.KNOTS,
    bearingDegrees: Boolean = false,
    approximate: Boolean = false,
    showSky: Boolean = true,
    controlsVisible: Boolean = true,
    onTarget: (Instant) -> Unit = {},
    onNow: () -> Unit = {},
    fixedWindow: Boolean = false,
    chartModel: ChartModel? = null,
    modelYear: Int = now.atZone(ZoneOffset.UTC).year,
    parked: Boolean = false,
    inputModifier: (String) -> Modifier = { Modifier },
) {
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember(station.timezone) {
        station.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
    }
    val clock24 = DateFormat.is24HourFormat(LocalContext.current)
    val clock = remember(locale, clock24) {
        DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(
                locale,
                if (clock24) "Hm" else "hm"
            ), locale
        )
    }
    val date = remember(locale) {
        DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(
                locale,
                "MMMd"
            ), locale
        )
    }
    val scene = remember(series, days, thresholdKnots, showSky) {
        ChartScene(
            series,
            days,
            thresholdKnots,
            showSky
        )
    }
    val first = scene.points.firstOrNull()?.at
    val last = scene.points.lastOrNull()?.at
    val skyScene by produceState<SkyScene?>(null, station.id, first, last, days) {
        if (first != null && last != null && station.latitude in -90.0..90.0 && station.longitude in -180.0..180.0) {
            value = withContext(Dispatchers.Default) {
                SkyScene(
                    station.latitude,
                    station.longitude,
                    first,
                    last,
                    days
                )
            }
        }
    }
    val latestTime by rememberUpdatedState(selected)
    var renderedSky by remember(station.id, showSky) { mutableStateOf<Pair<Long, SkyFrame?>?>(null) }
    val sky = renderedSky?.second
    LaunchedEffect(skyScene, showSky) {
        val source = skyScene ?: return@LaunchedEffect
        if (showSky) snapshotFlow { latestTime.epochSecond / 10 }.conflate().collect { bucket ->
            val frame =
                withContext(Dispatchers.Default) { source.frame(Instant.ofEpochSecond(bucket * 10)) }
            if (latestTime.epochSecond / 10 == bucket) renderedSky = bucket to frame
        }
    }
    val sampled = remember(scene, selected, useFeet, speedUnit, bearingDegrees, locale) {
        scene.lead(selected, useFeet, speedUnit, bearingDegrees, locale)
    }
    var precise by remember(scene, chartModel, useFeet, speedUnit, bearingDegrees, locale) {
        mutableStateOf<Pair<Instant, ChartLead>?>(null)
    }
    LaunchedEffect(scene, chartModel, useFeet, speedUnit, bearingDegrees, locale) {
        if (chartModel != null) snapshotFlow { latestTime }.conflate().collect { at ->
            val reading = withContext(Dispatchers.Default) {
                scene.lead(
                    at,
                    useFeet,
                    speedUnit,
                    bearingDegrees,
                    locale,
                    chartModel
                )
            }
            if (latestTime == at) precise = at to reading
        }
    }
    val lead = precise?.takeIf { it.first == selected }?.second ?: sampled
    val reach by produceState(false, chartModel, modelYear, zone) {
        value = false
        if (chartModel is ChartModel.Current) value =
            withContext(Dispatchers.Default) { chartModel.isReach(modelYear, zone) }
    }
    val stateWord = localizedState(lead.state, resources)
    val glyph = when {
        lead.set.isNotEmpty() -> "↑"
        lead.state.startsWith("High") -> "⤒"
        lead.state.startsWith("Low") -> "⤓"
        lead.state.startsWith("Rising") -> "↗"
        lead.state.startsWith("Falling") -> "↘"
        lead.state == "Slack" -> "→│←"
        scene.derived && lead.state == "Flooding" -> "→"
        scene.derived && lead.state == "Ebbing" -> "←"
        else -> ""
    }
    val relativeDays =
        listOf(R.string.panel_yesterday, R.string.panel_today, R.string.panel_tomorrow).map(
            resources::getString
        )
    val local = selected.atZone(zone)
    val timeText = clock.format(local)
    val dateTime =
        date.format(local) + " · " + (if (timeText.length > 1 && !timeText[1].isDigit()) "\u2007" else "") + timeText
    val unitName = when (lead.unit) {
        "m" -> R.string.spoken_metres
        "ft" -> R.string.spoken_feet
        "kn" -> R.string.spoken_knots
        "km/h" -> R.string.spoken_kilometres_per_hour
        "m/s" -> R.string.spoken_metres_per_second
        else -> null
    }?.let(resources::getString).orEmpty()
    val description = listOf(
        stateWord,
        lead.set,
        lead.value,
        unitName,
        dateTime.replace("\u2007", ""),
        DateTimeFormatter.ofPattern("z", locale).format(local)
    ).filter(String::isNotBlank).joinToString(", ")
    val candidate = scene.commentary(selected)
    val density = LocalDensity.current
    val extra = (160f * (density.fontScale - 1)).coerceAtLeast(0f)
    val peakRow =
        reach && scene.water.any { it.stop == ChartStop.Flood || it.stop == ChartStop.Ebb }
    val height = (if (peakRow) 433 else 413) + extra
    val resolver = LocalContext.current.contentResolver
    val reduceMotion = remember(resolver) {
        android.provider.Settings.Global.getFloat(
            resolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
    var leadSize by remember { mutableStateOf(IntSize.Zero) }
    val twinkle = produceState(0.0, sky?.stars?.isNotEmpty() == true, reduceMotion) {
        if (!reduceMotion && sky?.stars?.isNotEmpty() == true) while (true) {
            value = android.os.SystemClock.uptimeMillis() / 1000.0
            kotlinx.coroutines.delay(125)
        } else value = 0.0
    }
    BoxWithConstraints(
        Modifier.testTag(if (sky != null && renderedSky?.first == selected.epochSecond / 10) "detailSkyReady" else "detailSkyPending").fillMaxWidth()
            .tourAnchor(TourStep.STARS)
            .height(height.dp).clipToBounds().background(Color(0xff06182c))
    ) {
        val width = maxWidth.value
        val stop = candidate?.takeIf {
            !fixedWindow || first != null && last != null &&
                    TimelineWindow(first, last).clamp(it.at, width.toDouble()) == it.at
        }
        val warning = lead.rate?.let { Color(warningInk(it, sky)) }
        // Continuous chunks share a decoded model. A bounded source publishes a whole replacement block.
        val scale = key(station.id, if (fixedWindow) series else chartModel) {
            var held by remember { mutableStateOf(scene.fit(selected, width, fixedWindow)) }
            LaunchedEffect(selected, width) {
                val next = scene.fit(selected, width, fixedWindow)
                if (held.shouldAdopt(next, if (scene.tide) 1.06 else 1.05)) held = next
            }
            val tween = tween<Float>(if (reduceMotion) 0 else 300, easing = Easing { it * it * (3 - 2 * it) })
            val centre by animateFloatAsState(held.centre, tween, label = "Plot centre")
            val half by animateFloatAsState(held.half, tween, label = "Plot span")
            ChartScale(centre, half)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(density.density, density.density)
                ChartDrawing(native, width, selected, now).sky(
                    sky, 186f + extra, twinkle.value,
                    leadSize.width / density.density, leadSize.height / density.density
                )
                native.restore()
            }
        }
        Canvas(Modifier.fillMaxSize().then(modifier).then(inputModifier(description))) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(density.density, density.density)
                val drawing = ChartDrawing(native, width, selected, now)
                native.translate(0f, extra)
                drawing.plot(
                    scene,
                    scale,
                    days,
                    zone,
                    locale,
                    clock,
                    useFeet,
                    speedUnit,
                    peakRow,
                    alertMoments,
                    skyScene?.peaks.orEmpty(),
                    showSky,
                    controlsVisible && stop != null,
                    parked,
                    relativeDays
                )
                native.restore()
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter).onSizeChanged { leadSize = it }.tourAnchor(TourStep.READ)
            .padding(top = 12.dp).clearAndSetSemantics { contentDescription = description },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    stateWord,
                    color = warning ?: Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
                val ink = when {
                    warning != null -> warning
                    approximate -> Color(0xffef6f4a)
                    lead.state == "Slack" -> Color(0xff88b868)
                    lead.state.startsWith("High") || lead.state.startsWith("Rising") -> Color(
                        0xff2dd4bf
                    )

                    lead.state.startsWith("Low") || lead.state.startsWith("Falling") -> Color(
                        0xfffbbf24
                    )

                    lead.state.lowercase().contains("ebb") -> Color(0xffe8a33d)
                    else -> Color(0xff4a9fd8)
                }
                if (glyph.isNotEmpty()) Text(
                    glyph,
                    Modifier.rotate((lead.bearing ?: 0.0).toFloat()),
                    color = ink
                )
                if (lead.set.isNotBlank()) Text(
                    lead.set,
                    color = ink,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (lead.value.isNotEmpty()) Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val ink = if (approximate) Color(0xffef6f4a) else Color.White
                Text(
                    (if (approximate) "~" else "") + lead.value,
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Medium,
                    color = ink
                )
                Text(lead.unit, Modifier.padding(bottom = 4.dp), fontSize = 21.sp, color = ink)
            }
            Text(dateTime, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        if (controlsVisible) {
            val away = Duration.between(now, selected).abs() > Duration.ofSeconds(200)
            val pillTop = (112f + extra).dp
            if (stop != null) {
                val duration = Duration.between(selected, stop.at).toMinutes().coerceAtLeast(0)
                val countdown =
                    if (duration < 60) "${duration}m" else "${duration / 60}h ${duration % 60}m"
                val prefix =
                    if (approximate && stop.stop != ChartStop.Sunrise && stop.stop != ChartStop.Sunset) "~" else ""
                val pillInk = if (stop.stop == ChartStop.Rise || stop.stop == ChartStop.Fall) {
                    val i = scene.points.binarySearchBy(stop.at) { it.at }
                    Color(warningInk(scene.points.getOrNull(i)?.strength ?: 0.6, sky))
                } else Color.White
                val text = listOf(
                    prefix + resources.getString(stop.stop.resource()),
                    stop.stop.glyph,
                    countdown
                ).filter { it.isNotBlank() }.joinToString(" ")
                val spoken =
                    resources.getString(if (stop.stop == ChartStop.Close) R.string.chart_closes else stop.stop.resource())
                val hours = duration.toInt() / 60
                val minutes = duration.toInt() % 60
                val spokenDuration = listOfNotNull(
                    if (hours > 0) resources.getQuantityString(
                        R.plurals.chart_hours,
                        hours,
                        hours
                    ) else null,
                    if (minutes > 0 || hours == 0) resources.getQuantityString(
                        R.plurals.chart_minutes,
                        minutes,
                        minutes
                    ) else null,
                ).joinToString(" ")
                Box(Modifier.align(Alignment.TopCenter).padding(top = pillTop)) {
                    ChartPill(
                        text,
                        resources.getString(R.string.chart_in, spoken, spokenDuration),
                        Modifier.testTag("detailCommentary")
                            .widthIn(max = if (away) (width.dp - 152.dp).coerceAtLeast(64.dp) else width.dp - 16.dp),
                        pillInk
                    ) { onTarget(stop.at) }
                }
            }
            if (away) Box(
                Modifier.align(if (selected > now) Alignment.TopStart else Alignment.TopEnd)
                    .padding(top = pillTop, start = 8.dp, end = 8.dp)
            ) {
                val word = resources.getString(R.string.chart_now)
                ChartPill(
                    if (selected > now) "‹ $word" else "$word ›",
                    resources.getString(R.string.chart_return),
                    Modifier.testTag("detailNow"),
                    onClick = onNow
                )
            }
        }
    }
}

@Composable
private fun ChartPill(
    text: String,
    spoken: String,
    modifier: Modifier,
    ink: Color = Color.White,
    onClick: () -> Unit
) {
    TextButton(
        onClick,
        modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken },
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(
            text,
            Modifier.background(Color(0xc0253951), CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = ink,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun ChartStop.resource() = when (this) {
    ChartStop.High -> R.string.chart_high
    ChartStop.Low -> R.string.chart_low
    ChartStop.Flood -> R.string.chart_max_flood
    ChartStop.Ebb -> R.string.chart_max_ebb
    ChartStop.Rise -> R.string.chart_fast_rise
    ChartStop.Fall -> R.string.chart_fast_fall
    ChartStop.Slack -> R.string.card_slack
    ChartStop.Open, ChartStop.Close -> R.string.chart_window
    ChartStop.Sunrise -> R.string.panel_rise
    ChartStop.Sunset -> R.string.panel_set
}

private fun localizedState(state: String, resources: Resources): String {
    val id = when (state.substringBefore(' ')) {
        "High" -> R.string.chart_high
        "Low" -> R.string.chart_low
        "Max" -> if (state.endsWith("flood")) R.string.chart_max_flood else R.string.chart_max_ebb
        "Slack" -> R.string.card_slack
        "Flooding" -> R.string.card_flooding
        "Ebbing" -> R.string.card_ebbing
        "Rising" -> R.string.card_rising
        "Falling" -> R.string.card_falling
        else -> R.string.card_unavailable
    }
    return resources.getString(id) + if (state.startsWith("Rising ") || state.startsWith("Falling ")) " " + state.substringAfter(
        ' '
    ) else ""
}
