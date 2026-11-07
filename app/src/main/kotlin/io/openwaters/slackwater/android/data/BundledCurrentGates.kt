package io.openwaters.slackwater.android.data

import android.content.res.AssetManager
import io.openwaters.slackwater.android.core.prediction.CurrentGateEvidence
import io.openwaters.slackwater.android.core.prediction.CurrentGateFit
import io.openwaters.slackwater.android.core.prediction.CurrentGateMiss
import io.openwaters.slackwater.android.core.station.StationId
import org.json.JSONArray
import org.json.JSONObject

internal class BundledCurrentGates(private val assets: AssetManager) {
    private val records by lazy {
        val rows = assets.open("slackwater/chs-current-gates.json").bufferedReader().use { JSONArray(it.readText()) }
        (0 until rows.length()).map { rows.getJSONObject(it).toGateEvidence() }.associateBy { it.id }
    }

    operator fun get(id: StationId): CurrentGateEvidence? = records[id]
}

private fun JSONObject.toGateEvidence() = CurrentGateEvidence(
    id = StationId(getString("id")),
    fitDays = getDouble("fitDays"),
    recipe = opt("recipe") as? String,
    online = optBoolean("online"),
    slackBoundMinutes = (opt("slackBoundMinutes") as? Number)?.toInt(),
    provisionalSlackMinutes = (opt("provisionalSlackMinutes") as? Number)?.toInt(),
    onlineMiss = optJSONObject("onlineMiss")?.let {
        CurrentGateMiss(it.getString("clause"), (it.opt("value") as? Number)?.toDouble(), it.optBoolean("nearBar"))
    },
    missContext = opt("missContext") as? String,
    magnitudeNote = opt("magnitudeNote") as? String,
    tideReference = (opt("tideReference") as? String)?.let(::StationId),
)

internal fun CurrentGateEvidence.compatibleFit(record: JSONObject): CurrentGateFit? = compatibleFit(
    record.opt(ModelStore.STATION_FIELD) as? String,
    record.opt("recipe") as? String,
    (record.opt("fitDays") as? Number)?.toDouble(),
)
