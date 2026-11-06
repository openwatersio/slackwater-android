package io.openwaters.slackwater.android.core.station

/** A cached fix is reused only while it is at most this old. */
const val FIX_MAXIMUM_AGE_MILLIS = 600_000L

/** What the person has allowed. Approximate is Android's coarse-only grant. */
enum class LocationPermission { NOT_GRANTED, APPROXIMATE, PRECISE }

/** A position the platform reported, timed on the elapsed-realtime clock so a wall-clock change cannot age it. */
data class LocationFix(val coordinate: Coordinate, val accuracyMetres: Double?, val elapsedRealtimeMillis: Long) {
    fun isUsable(nowElapsedRealtimeMillis: Long): Boolean {
        val accuracy = accuracyMetres ?: return false
        if (!accuracy.isFinite() || accuracy <= 0) return false
        if (!coordinate.latitude.isFinite() || !coordinate.longitude.isFinite()) return false
        val age = nowElapsedRealtimeMillis - elapsedRealtimeMillis
        return age in 0..FIX_MAXIMUM_AGE_MILLIS
    }
}

/**
 * What the list knows about location at one moment.
 *
 * Android's rationale flag is false before the first ask, after a permanent denial, and after an "Only this time"
 * grant lapses, so it cannot say on its own whether the prompt would still appear. [promptRefused] is set only when a
 * prompt the person raised came back denied with the platform declining to ask again; until then the slot offers
 * the prompt, and a tap that the system refuses moves it to the Settings card.
 */
data class LocationInputs(
    val permission: LocationPermission,
    val promptRefused: Boolean = false,
    val promptShowing: Boolean = false,
    val fix: LocationFix? = null,
    val requestFailed: Boolean = false,
)

/** A denial answered faster than this cannot have come from a person reading the system dialog. */
const val PROMPT_WITHOUT_DIALOG_MILLIS = 300L

/**
 * Whether the system has stopped showing the location prompt, read from one request's answer. [rationaleBefore] and
 * [rationaleAfter] are the platform's rationale flag around the request. A denial that turns the flag from true to
 * false is the second, permanent one. A denial that leaves it false is permanent only when it came back too fast for
 * a dialog to have shown; otherwise it is a first denial or a dismissed dialog, and the prompt will appear again.
 */
fun promptRefused(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, answeredAfterMillis: Long): Boolean =
    !granted && !rationaleAfter && (rationaleBefore || answeredAfterMillis < PROMPT_WITHOUT_DIALOG_MILLIS)

/** The My Location slot's one state. Only [Located] makes a fix, and only while location is allowed. */
sealed interface LocationSlot {
    data class Located(val fix: LocationFix, val approximate: Boolean) : LocationSlot

    /** Allowed but no usable fix yet, or the system prompt is up: a spinner and "Finding your location…". */
    data object Locating : LocationSlot

    /** Allowed, but the request failed: "Location unavailable", naming the fallback origin, with no spinner. */
    data object Failed : LocationSlot

    /** Not allowed, and the prompt may still appear: the "Find tides near me" card. */
    data object NotDetermined : LocationSlot

    /** Not allowed, and the last prompt was refused without the system asking: the "Go to Settings" card. */
    data object Denied : LocationSlot
}

fun locationSlot(inputs: LocationInputs, nowElapsedRealtimeMillis: Long): LocationSlot {
    if (inputs.promptShowing) return LocationSlot.Locating
    if (inputs.permission == LocationPermission.NOT_GRANTED) {
        return if (inputs.promptRefused) LocationSlot.Denied else LocationSlot.NotDetermined
    }
    val fix = inputs.fix?.takeIf { it.isUsable(nowElapsedRealtimeMillis) }
    return when {
        fix != null -> LocationSlot.Located(fix, approximate = inputs.permission == LocationPermission.APPROXIMATE)
        inputs.requestFailed -> LocationSlot.Failed
        else -> LocationSlot.Locating
    }
}

/** The coordinate the list ranks from, which only a located slot supplies. */
val LocationSlot.fixCoordinate: Coordinate? get() = (this as? LocationSlot.Located)?.fix?.coordinate

/**
 * "48.423°N, 123.371°W": three decimals and hemisphere letters, with a period in every locale, as iOS prints it.
 * The hemisphere follows the sign before rounding, so -0.0004 reads "0.000°S", as it does on iOS.
 */
fun formatCoordinate(coordinate: Coordinate): String {
    require(coordinate.latitude.isFinite() && coordinate.longitude.isFinite()) { "Not a position: $coordinate" }
    // Rounds the double's exact binary value, as iOS's printf does, not its shortest decimal form.
    fun part(value: Double, positive: Char, negative: Char) =
        "${java.math.BigDecimal(kotlin.math.abs(value)).setScale(3, java.math.RoundingMode.HALF_EVEN).toPlainString()}°" +
            if (value >= 0) positive else negative
    return "${part(coordinate.latitude, 'N', 'S')}, ${part(coordinate.longitude, 'E', 'W')}"
}
