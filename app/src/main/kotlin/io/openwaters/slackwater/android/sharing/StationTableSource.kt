package io.openwaters.slackwater.android.sharing

import android.content.res.AssetManager
import android.net.Uri
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.PredictionRepository
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.BundledTideDatabase
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The free schedule and both export periods read the same bundled catalog and local model store. */
internal class StationTableSource(private val assets: AssetManager, private val predictions: PredictionRepository) {
    private val tides = BundledTideDatabase(assets)
    private fun asset(name: String) = assets.open("slackwater/$name.json").bufferedReader().use { it.readText() }
    private val slugs by lazy { JSONObject(asset("slugs")) }
    private val references by lazy {
        val gates = JSONArray(asset("chs-gates"))
        (0 until gates.length()).associate { index ->
            val gate = gates.getJSONObject(index)
            gate.getString("id") to gate.getString("referenceName")
        }
    }

    suspend fun load(station: StationIdentity, period: PrintSpan, anchor: Instant, printedAt: Instant): PrintableTable =
        withContext(Dispatchers.Default) {
            val current = station.series == StationSeries.Current
            val rawId = station.id.value.removePrefix("current:")
            val slug = slugs.getJSONObject(if (current) "current" else "tide").optString(rawId)
            val link = slug.takeIf { it.isNotBlank() }?.let {
                Uri.Builder().scheme("https").authority("slackwater.xyz")
                    .appendPath(if (current) "currents" else "tides").appendPath(it).build().toString()
            }
            val record = if (station.kind == StationKind.Tide) tides.station(station.id) else null
            val datum = if (station.kind == StationKind.CanadianTide) "LLWLT" else record?.chartDatum
            printableTable(station, period, anchor, printedAt, predictions, datum = datum, link = link,
                publisher = record?.source?.name,
                referencePortName = if (station.kind == StationKind.DerivedGate) references[rawId] else null,
                attribution = record?.attribution)
        }
}
