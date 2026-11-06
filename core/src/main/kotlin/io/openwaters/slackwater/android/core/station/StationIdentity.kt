package io.openwaters.slackwater.android.core.station

@JvmInline
value class StationId(val value: String)

enum class StationKind { Tide, Current, CanadianTide, DerivedGate, CanadianCurrent, Retired, Restricted }

data class StationIdentity(
    val id: StationId,
    val name: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val kind: StationKind,
    val aliases: List<String> = emptyList(),
    val timezone: String? = null,
    val source: String? = null,
    val license: String? = null,
)

data class StationPlace(val series: StationSeries, val name: String)

enum class StationSeries { Tide, Current }

val StationIdentity.series: StationSeries?
    get() = when (kind) {
        StationKind.Tide, StationKind.CanadianTide -> StationSeries.Tide
        StationKind.Current, StationKind.DerivedGate, StationKind.CanadianCurrent -> StationSeries.Current
        StationKind.Retired, StationKind.Restricted -> null
    }

/** Namesake groups use the display name and series. Persist the station ID across renames. */
val StationIdentity.place: StationPlace?
    get() = series?.let { StationPlace(it, java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)) }
