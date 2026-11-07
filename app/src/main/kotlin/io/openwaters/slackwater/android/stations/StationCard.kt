package io.openwaters.slackwater.android.stations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.plotZone
import io.openwaters.slackwater.android.core.station.CardCurrentState
import io.openwaters.slackwater.android.core.station.CardCurve
import io.openwaters.slackwater.android.core.station.CardReading
import io.openwaters.slackwater.android.core.station.CurveExtreme
import io.openwaters.slackwater.android.core.station.SpeedUnitKind
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.formatHeightValue
import io.openwaters.slackwater.android.core.station.formatNauticalMiles
import io.openwaters.slackwater.android.core.station.formatSet
import io.openwaters.slackwater.android.core.station.formatSpeedValue
import io.openwaters.slackwater.android.data.HeightUnits
import io.openwaters.slackwater.android.data.ReadingPreferences
import io.openwaters.slackwater.android.data.SetFormat
import io.openwaters.slackwater.android.data.SpeedUnit
import io.openwaters.slackwater.compassIndex
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

// List §6.2 colours. Colour encodes the water's state, never the station type.
private val RisingTeal = Color(0xFF2DD4BF)
private val FallingAmber = Color(0xFFFBBF24)
private val FloodBlue = Color(0xFF4A9FD8)
private val EbbAmber = Color(0xFFE8A33D)
private val GoGreen = Color(0xFF88B868)

private val SpeedUnit.kind get() = when (this) {
    SpeedUnit.KNOTS -> SpeedUnitKind.KNOTS
    SpeedUnit.KILOMETRES_PER_HOUR -> SpeedUnitKind.KILOMETRES_PER_HOUR
    SpeedUnit.METRES_PER_SECOND -> SpeedUnitKind.METRES_PER_SECOND
}

/** What the right side of a card prints and says, in the person's units. */
private class ReadingText(val value: String?, val state: String, val stateColor: Color, val glyph: String?,
                          val glyphColor: Color, val glyphRotation: Float, val spoken: String)

@Composable
private fun readingText(reading: CardReading, preferences: ReadingPreferences): ReadingText? {
    val locale = LocalConfiguration.current.locales[0]
    return when (reading) {
        is CardReading.Tide -> {
            val imperial = preferences.heightUnits(locale) == HeightUnits.IMPERIAL
            val value = formatHeightValue(reading.heightMetres, imperial, locale)
            val unit = stringResource(if (imperial) R.string.unit_feet else R.string.unit_metres)
            val spokenUnit = stringResource(if (imperial) R.string.spoken_feet else R.string.spoken_metres)
            val state = stringResource(if (reading.rising) R.string.card_rising else R.string.card_falling)
            ReadingText(
                value = stringResource(R.string.card_value_unit, value, unit),
                state = state,
                stateColor = MaterialTheme.colorScheme.onSurface,
                glyph = if (reading.rising) "↗" else "↘",
                glyphColor = if (reading.rising) RisingTeal else FallingAmber,
                glyphRotation = 0f,
                spoken = stringResource(R.string.card_spoken_reading, value, spokenUnit, state.lowercase(locale)),
            )
        }
        is CardReading.Current -> {
            val unit = preferences.speedUnit
            val value = formatSpeedValue(reading.speedKnots, unit.kind, locale)
            val printed = stringResource(when (unit) {
                SpeedUnit.KNOTS -> R.string.unit_knots
                SpeedUnit.KILOMETRES_PER_HOUR -> R.string.unit_kilometres_per_hour
                SpeedUnit.METRES_PER_SECOND -> R.string.unit_metres_per_second
            })
            val spokenUnit = stringResource(when (unit) {
                SpeedUnit.KNOTS -> R.string.spoken_knots
                SpeedUnit.KILOMETRES_PER_HOUR -> R.string.spoken_kilometres_per_hour
                SpeedUnit.METRES_PER_SECOND -> R.string.spoken_metres_per_second
            })
            val state = stateWord(reading.state)
            val compass = stringArrayResource(R.array.pdf_compass)
            val set = reading.setDegrees?.let {
                if (preferences.setFormat == SetFormat.DEGREES) formatSet(it, asBearing = true) else compass[compassIndex(it)]
            }
            val spokenState = listOfNotNull(state.lowercase(locale), set?.let { stringResource(R.string.card_spoken_set, it) })
                .joinToString(" ")
            ReadingText(
                value = stringResource(if (reading.approximate) R.string.card_value_unit_approximate else R.string.card_value_unit, value, printed),
                state = if (set != null) stringResource(R.string.card_state_set, state, set) else state,
                stateColor = if (reading.state == CardCurrentState.SLACK) GoGreen else MaterialTheme.colorScheme.onSurface,
                // The arrow points where the water goes; below the neutral speed a dot replaces it.
                glyph = if (reading.setDegrees != null) "↑" else "•",
                glyphColor = when {
                    reading.setDegrees == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    reading.state == CardCurrentState.SLACK -> GoGreen
                    reading.state == CardCurrentState.FLOODING -> FloodBlue
                    else -> EbbAmber
                },
                glyphRotation = reading.setDegrees?.toFloat() ?: 0f,
                spoken = stringResource(R.string.card_spoken_reading,
                    if (reading.approximate) stringResource(R.string.card_spoken_approximate, value) else value, spokenUnit, spokenState),
            )
        }
        is CardReading.Derived -> {
            val state = stateWord(reading.phase)
            ReadingText(
                value = null,
                state = state,
                stateColor = if (reading.phase == CardCurrentState.SLACK) GoGreen else MaterialTheme.colorScheme.onSurface,
                glyph = when (reading.phase) {
                    CardCurrentState.FLOODING -> "→"
                    CardCurrentState.EBBING -> "←"
                    CardCurrentState.SLACK -> "•"
                },
                glyphColor = when (reading.phase) {
                    CardCurrentState.FLOODING -> FloodBlue
                    CardCurrentState.EBBING -> EbbAmber
                    CardCurrentState.SLACK -> GoGreen
                },
                glyphRotation = 0f,
                spoken = state,
            )
        }
        CardReading.NotDownloaded -> null
        CardReading.Unavailable -> null
    }
}

@Composable
private fun stateWord(state: CardCurrentState) = stringResource(when (state) {
    CardCurrentState.SLACK -> R.string.card_slack
    CardCurrentState.FLOODING -> R.string.card_flooding
    CardCurrentState.EBBING -> R.string.card_ebbing
})

/**
 * One station as a single tap target, or a plain element when [onOpen] is null: name and region on the left, the
 * live reading on the right, and the 25-hour curve behind them. Reading and curve are computed when the card
 * appears and again whenever [now] moves, which the list advances every 60 seconds.
 */
@Composable
fun StationCard(
    station: StationIdentity,
    distanceKm: Double?,
    now: Instant,
    preferences: ReadingPreferences,
    load: suspend (at: Instant, thresholdKnots: Double) -> CardData,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    var data by remember(station.id) { mutableStateOf<CardData?>(null) }
    // Once a station has drawn a curve its card keeps that height while it stays on screen, so a refresh that finds
    // none does not shrink it.
    var hadCurve by remember(station.id) { mutableStateOf(false) }
    // A changed comfort current rebuilds the curve's windows at once, not on the next tick.
    val threshold = preferences.effectiveThresholdKn(station.id)
    LaunchedEffect(station.id, now, threshold) {
        val loaded = load(now, threshold)
        if (loaded.curve != null) hadCurve = true
        data = loaded
    }
    val reading = data?.reading
    val curve = data?.curve
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember(station.timezone) { station.plotZone() }
    val imperial = preferences.heightUnits(locale) == HeightUnits.IMPERIAL
    val curveValue: (CurveExtreme) -> String? = { extreme ->
        when (extreme.kind) {
            CurveExtreme.Kind.TIDE -> formatHeightValue(extreme.value, imperial, locale)
            CurveExtreme.Kind.CURRENT -> (if (data?.approximate == true) "~" else "") +
                formatSpeedValue(abs(extreme.value), preferences.speedUnit.kind, locale)
            CurveExtreme.Kind.DERIVED -> null
        }
    }
    val spokenCurve = curve?.let { spokenCurve(it, zone, locale, imperial, preferences.speedUnit, data?.approximate == true) }
    val distance = distanceKm?.let { stringResource(R.string.list_distance_nm, formatNauticalMiles(it, locale)) }
    val text = reading?.let { readingText(it, preferences) }
    val notDownloaded = reading == CardReading.NotDownloaded
    val status = when (reading) {
        CardReading.NotDownloaded -> stringResource(R.string.card_not_downloaded)
        CardReading.Unavailable -> stringResource(R.string.card_unavailable)
        else -> null
    }
    val identity = listOfNotNull(station.name, station.region.ifEmpty { null }, distance).joinToString(", ")
    val spoken = listOfNotNull(identity, text?.spoken, status, spokenCurve).joinToString(". ")
    // Every card holds the curve's height while it loads, and a waiting Canadian card keeps it with a data-free
    // skeleton, so the list does not jump when data arrives. A station with no curve at all settles once to the
    // shorter card.
    val showsCurve = data == null || curve != null || notDownloaded || hadCurve
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (showsCurve) 168.dp else 96.dp)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (onOpen != null) {
                    role = Role.Button
                    onClick { onOpen(); true }
                }
                if (actions.isNotEmpty()) customActions = actions
            },
    ) {
        // The curve sits at the card's foot below the identity rows, which grow with the text size, at a fixed height.
        Column(verticalArrangement = Arrangement.SpaceBetween) {
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = if (showsCurve) 0.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // An unfitted Canadian card shows its identity dimmed, with no reading.
                Column(Modifier.weight(1f).alpha(if (notDownloaded) 0.82f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(station.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val region = station.region.ifEmpty { null }
                    val line = when {
                        region != null && distance != null -> stringResource(R.string.list_region_distance, region, distance)
                        else -> region ?: distance
                    }
                    line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (text != null) {
                    Column(Modifier.widthIn(max = 180.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        text.value?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text.state, style = MaterialTheme.typography.bodyMedium, color = text.stateColor)
                            text.glyph?.let { Text(it, color = text.glyphColor, modifier = Modifier.rotate(text.glyphRotation)) }
                        }
                    }
                }
            }
            if (showsCurve) {
                val ink = MaterialTheme.colorScheme.onSurface
                val area = Modifier.fillMaxWidth().height(CURVE_HEIGHT)
                when {
                    curve != null -> CardCurveCanvas(curve, zone, data?.approximate == true, curveValue, ink, area)
                    // The skeleton means data is on its way; a curve that has gone leaves its space empty.
                    data == null || notDownloaded -> CardCurveSkeleton(ink, area)
                    else -> Spacer(area)
                }
            }
        }
    }
}

/** The curve's band beneath a card's identity rows (list §6.4). */
private val CURVE_HEIGHT = 114.dp

/**
 * "25-hour curve: high tide 6.2 feet at 8:33 AM, …": a tide's turns; a current's peaks with their direction and its
 * slacks, in time order; a derived gate's slack times, which are the only times it has. Null when the span holds none.
 */
@Composable
private fun spokenCurve(
    curve: CardCurve, zone: ZoneId, locale: java.util.Locale, imperial: Boolean, speed: SpeedUnit,
    approximate: Boolean,
): String? {
    val formatter = remember(locale, zone) { cardTimeFormatter(locale, zone) }
    val height = stringResource(if (imperial) R.string.spoken_feet else R.string.spoken_metres)
    val speedUnit = stringResource(when (speed) {
        SpeedUnit.KNOTS -> R.string.spoken_knots
        SpeedUnit.KILOMETRES_PER_HOUR -> R.string.spoken_kilometres_per_hour
        SpeedUnit.METRES_PER_SECOND -> R.string.spoken_metres_per_second
    })
    val flood = stringResource(R.string.curve_spoken_flood)
    val ebb = stringResource(R.string.curve_spoken_ebb)
    val high = stringResource(R.string.alert_high)
    val low = stringResource(R.string.alert_low)
    val slack = stringResource(R.string.card_slack)
    val approximately = stringResource(R.string.card_spoken_approximate, "%s")
    val item = stringResource(R.string.curve_spoken_item, "%1\$s", "%2\$s")
    val slacks = curve.slacks.map { it to slack }
    val moments = if (curve.schematic) slacks else curve.extremes.map { extreme ->
        extreme.time to when (extreme.kind) {
            CurveExtreme.Kind.TIDE -> "${if (extreme.high) high else low} ${formatHeightValue(extreme.value, imperial, locale)} $height"
            else -> {
                val value = formatSpeedValue(abs(extreme.value), speed.kind, locale)
                val printed = if (approximate) approximately.format(value) else value
                "$printed $speedUnit ${if (extreme.value >= 0) flood else ebb}"
            }
        }
    } + if (curve.includesZero) slacks else emptyList()
    val items = moments.sortedBy { it.first }.map { (time, said) -> item.format(said, formatter.format(time)) }
    if (items.isEmpty()) return null
    return stringResource(R.string.curve_spoken, items.joinToString(", "))
}
