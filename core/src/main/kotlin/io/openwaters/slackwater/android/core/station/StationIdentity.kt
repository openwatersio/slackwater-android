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
