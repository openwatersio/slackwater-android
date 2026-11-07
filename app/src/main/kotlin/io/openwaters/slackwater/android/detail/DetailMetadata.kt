package io.openwaters.slackwater.android.detail

import android.content.res.AssetManager
import android.net.Uri
import io.openwaters.slackwater.android.core.timeline.isNearSessionNow
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.core.prediction.CurrentGateEvidence
import io.openwaters.slackwater.android.data.BundledCurrentGates
import io.openwaters.slackwater.android.data.BundledCurrentRecords
import io.openwaters.slackwater.android.data.BundledTideDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class DetailMetadata(
    val route: String?, val pairedTide: StationId?, val reference: String?,
    val credit: String?, val gate: CurrentGateEvidence?, val datum: String?,
    val highLag: Int?, val lowLag: Int?, val meanFlow: Double?,
    val publisher: String?, val flood: Double?, val magnitudeNote: String?,
)

internal class DetailRecords(private val assets: AssetManager) {
    private fun json(name: String) = assets.open("slackwater/$name.json").bufferedReader().use { it.readText() }
    private val routes by lazy { JSONObject(json("slugs")) }
    private val tides by lazy { BundledTideDatabase(assets) }
    private val currents by lazy { BundledCurrentRecords(assets) }
    private fun gates(name: String) = JSONArray(json(name)).let { rows ->
        (0 until rows.length()).associate { i -> rows.getJSONObject(i).let { it.getString("id") to it } }
    }
    private val derived by lazy { gates("chs-gates") }
    private val measured = BundledCurrentGates(assets)
    private val catalog = io.openwaters.slackwater.android.data.BundledStationCatalog(assets)

    suspend fun destination(uri: Uri): Pair<StationId, Instant?>? {
        internalStationId(uri)?.let { id ->
            val station = catalog.resolve(id)
            return if (station is StationResolution.Unknown) null else station.id to null
        }
        if (uri.scheme != "https" || !uri.authority.equals("slackwater.xyz", ignoreCase = true)) return null
        val parts = uri.pathSegments
        if (parts.size !in 2..3) return null
        val tide = when (parts[0]) { "tides" -> true; "currents" -> false; else -> return null }
        val group = routes.getJSONObject(if (tide) "tide" else "current")
        val rawId = group.keys().asSequence().firstOrNull { group.optString(it) == parts[1] }
            ?: routes.optJSONObject("former")?.optJSONObject(if (tide) "tide" else "current")?.optString(parts[1])?.takeIf { group.has(it) }
            ?: return null
        val id = StationId(if (tide || rawId.startsWith("chs-")) rawId else "current:$rawId")
        val time = parts.getOrNull(2)?.let {
            try { java.time.OffsetDateTime.parse(it).takeIf { stamp -> stamp.year in 1..9999 }?.toInstant() ?: return null } catch (_: java.time.format.DateTimeParseException) { return null }
        }
        return id to time
    }

    fun metadata(station: StationIdentity): DetailMetadata {
        val id = station.id.value.removePrefix("current:")
        val tide = station.series == StationSeries.Tide || (station.series == null && routes.getJSONObject("tide").has(id))
        val slug = routes.getJSONObject(if (tide) "tide" else "current").optString(id).takeIf { it.isNotBlank() }
        val route = slug?.let { "https://slackwater.xyz/" + (if (tide) "tides/" else "currents/") + Uri.encode(it) }
        val gate = if (station.kind == StationKind.CanadianCurrent) measured[station.id] else null
        val row = when (station.kind) {
            StationKind.DerivedGate -> derived[id]
            StationKind.Current -> currents[id]
            else -> null
        }
        val record = if (station.kind == StationKind.Tide) tides.station(station.id) else null
        val paired = row?.optString(if (station.kind == StationKind.DerivedGate) "reference" else "tideReference")?.takeIf(String::isNotBlank)
        return DetailMetadata(route, gate?.tideReference ?: paired?.let(::StationId), record?.tideOffsets?.reference ?: row?.optString("reference")?.takeIf(String::isNotBlank),
            record?.attribution ?: station.source, gate, record?.chartDatum,
            row?.takeIf { it.has("hwLagMinutes") }?.getInt("hwLagMinutes"),
            row?.takeIf { it.has("lwLagMinutes") }?.getInt("lwLagMinutes"),
            row?.takeIf { it.has("meanFlow") }?.getDouble("meanFlow"), record?.source?.name,
            row?.takeIf { it.has("floodDirection") }?.getDouble("floodDirection"),
            gate?.magnitudeNote ?: row?.optString("magnitudeNote")?.takeIf(String::isNotBlank))
    }
}


internal fun internalStationId(uri: Uri): StationId? {
    if (uri.scheme != "slackwater" || uri.authority != "station") return null
    val id = uri.path?.removePrefix("/") ?: return null
    return StationId(id).takeIf { id.isNotBlank() && id.split('/').none(String::isEmpty) }
}

internal fun internalStationLink(id: StationId): Uri {
    val encoded = id.value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        val c = (byte.toInt() and 255).toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~") c.toString()
        else "%%%02X".format(Locale.ROOT, byte.toInt() and 255)
    }
    return Uri.parse("slackwater://station/$encoded")
}

internal fun detailShareLink(route: String?, selected: Instant?, now: Instant, zone: ZoneId): String? {
    if (route == null || selected == null || isNearSessionNow(selected, now)) return route
    val moment = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmxxx", Locale.ROOT).withZone(zone).format(selected)
    return "$route/$moment"
}
