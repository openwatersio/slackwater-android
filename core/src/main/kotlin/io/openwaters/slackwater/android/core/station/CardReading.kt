package io.openwaters.slackwater.android.core.station

import io.openwaters.slackwater.CurrentState
import io.openwaters.slackwater.ExtremeKind
import io.openwaters.slackwater.android.core.prediction.PredictionResult
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.prediction.StationCapabilities
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.prediction.readingAt
import io.openwaters.slackwater.compassPoint
import io.openwaters.slackwater.readTide
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Below this full speed the water's direction means nothing, so the card shows a neutral dot instead of a set. */
const val CARD_NEUTRAL_SET_KNOTS = 0.05

/** A derived gate reads Slack within this of a derived slack, the timing's own uncertainty. */
val DERIVED_SLACK_WINDOW: Duration = Duration.ofMinutes(12)

/** The span a card loads around its moment: 1.5 tidal swings back and 2.5 ahead, one swing being 6.21 hours. */
fun cardSpan(at: Instant): TimeSpan {
    val swing = Duration.ofSeconds((6.21 * 3600).toLong())
    return TimeSpan(at.minus(swing.multipliedBy(3).dividedBy(2)), at.plus(swing.multipliedBy(5).dividedBy(2)))
}

enum class CardCurrentState { SLACK, FLOODING, EBBING }

/** What a station card shows on its right side at one moment (list §6.2). */
sealed interface CardReading {
    data class Tide(val heightMetres: Double, val rising: Boolean) : CardReading

    /**
     * [speedKnots] is the full water speed. [setDegrees] is null for the neutral dot: below
     * [CARD_NEUTRAL_SET_KNOTS], with no known bearing, or at slack when no cross-channel flow prints.
     * [approximate] marks an early or bounded Canadian model, printed with "~".
     */
    data class Current(
        val speedKnots: Double,
        val state: CardCurrentState,
        val setDegrees: Double?,
        val approximate: Boolean,
    ) : CardReading

    data class Derived(val phase: CardCurrentState) : CardReading

    /** No predictions on this device yet: a Canadian station waiting for its download. */
    data object NotDownloaded : CardReading

    /** Predictions exist but not for this moment, or the station cannot be read. */
    data object Unavailable : CardReading
}

fun cardReading(result: PredictionResult, at: Instant): CardReading = when (result) {
    is PredictionResult.Unavailable ->
        if (result.reason == PredictionResult.Reason.MISSING_DOWNLOAD) CardReading.NotDownloaded else CardReading.Unavailable
    is PredictionResult.Disjoint ->
        result.blocks.firstOrNull { at in it.coverage }?.let { cardReading(it, at) } ?: CardReading.Unavailable
    is PredictionResult.Ready -> if (at !in result.coverage) CardReading.Unavailable else readSeries(result, at)
}

private fun readSeries(result: PredictionResult.Ready, at: Instant): CardReading = when (val series = result.series) {
    is PredictionSeries.Tide -> readTide(at, series.points, series.rates, series.events)
        ?.let { CardReading.Tide(it.heightMetres, it.rising) } ?: CardReading.Unavailable
    is PredictionSeries.Current -> series.readingAt(at)?.let { reading ->
        val state = when (reading.state) {
            CurrentState.SLACK -> CardCurrentState.SLACK
            CurrentState.FLOODING, CurrentState.MAX_FLOOD -> CardCurrentState.FLOODING
            CurrentState.EBBING, CurrentState.MAX_EBB -> CardCurrentState.EBBING
        }
        // At slack the set shows only while water still crosses the channel at a speed that prints above zero.
        val set = reading.directionDegrees?.takeIf { reading.magnitudeKnots >= CARD_NEUTRAL_SET_KNOTS }
        val approximate = result.capabilities == StationCapabilities.PROVISIONAL_CURRENT ||
            result.capabilities == StationCapabilities.BOUNDED_CURRENT
        CardReading.Current(reading.magnitudeKnots, state, set, approximate)
    } ?: CardReading.Unavailable
    is PredictionSeries.Derived -> derivedPhase(series.slacks, at)?.let(CardReading::Derived) ?: CardReading.Unavailable
}

/** Slack near a derived slack; otherwise flooding toward a high-water slack, ebbing toward a low-water one. */
fun derivedPhase(slacks: List<PredictionSeries.DerivedSlack>, at: Instant): CardCurrentState? {
    if (slacks.isEmpty()) return null
    if (slacks.any { Duration.between(it.time, at).abs() <= DERIVED_SLACK_WINDOW }) return CardCurrentState.SLACK
    val next = slacks.firstOrNull { it.time > at }
    // Past the last slack, the water runs away from that slack's level.
    val rising = next?.let { it.referenceTurn == ExtremeKind.HIGH } ?: (slacks.last().referenceTurn == ExtremeKind.LOW)
    return if (rising) CardCurrentState.FLOODING else CardCurrentState.EBBING
}

// The conversions iOS and the web print with, so the same height reads the same everywhere.
private const val FEET_PER_METRE = 3.28084

private fun decimal(value: Double, places: Int, locale: Locale): String {
    // The shortest decimal form rounds half away from zero, as ICU does; a value printing as zero loses its sign.
    var rounded = BigDecimal(value.toString()).setScale(places, RoundingMode.HALF_UP)
    if (rounded.signum() == 0) rounded = rounded.abs()
    return NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        minimumFractionDigits = places
        maximumFractionDigits = places
    }.format(rounded)
}

/** Feet to one decimal or metres to two, without the unit. */
fun formatHeightValue(metres: Double, imperial: Boolean, locale: Locale): String =
    if (imperial) decimal(metres * FEET_PER_METRE, 1, locale) else decimal(metres, 2, locale)

enum class SpeedUnitKind(val perKnot: Double) { KNOTS(1.0), KILOMETRES_PER_HOUR(1.852), METRES_PER_SECOND(0.514444) }

/** One decimal in the chosen unit, without the unit. */
fun formatSpeedValue(knots: Double, unit: SpeedUnitKind, locale: Locale): String = decimal(knots * unit.perKnot, 1, locale)

/** A set as a 16-point compass direction ("SW") or a three-digit true bearing ("225°"). */
fun formatSet(degrees: Double, asBearing: Boolean): String {
    if (!asBearing) return compassPoint(degrees)
    val whole = ((Math.round(degrees) % 360 + 360) % 360).toInt()
    return String.format(Locale.ROOT, "%03d°", whole)
}
