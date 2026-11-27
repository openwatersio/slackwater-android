package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.HarmonicFit
import io.openwaters.slackwater.android.core.downloads.fitCanadianTideWindow
import io.openwaters.slackwater.iwls.ChsChunk
import io.openwaters.slackwater.iwls.ChsSample
import io.openwaters.slackwater.iwls.IwlsSampleGrid
import java.time.Instant
import java.time.ZoneId
import io.openwaters.slackwater.android.core.station.StationId
import org.json.JSONArray
import org.json.JSONObject

/** Publish a successful fit even when IWLS returned empty weeks. Detail can use it during the next download. */
internal suspend fun ModelStore.fitCanadianTide(id: StationId, now: Instant, stationZone: ZoneId, fetch: suspend (ChsChunk, IwlsSampleGrid) -> List<ChsSample>) {
    val model = fitCanadianTideWindow(now, stationZone, fetch)
    write(id, ModelKind.TIDE, model.toFitRecord())
}

internal fun HarmonicFit.toFitRecord(): JSONObject {
    val terms = JSONArray()
    for (term in constituents) {
        terms.put(JSONObject().put("name", term.name)
            .put("amplitude", term.amplitude).put("phase", term.phaseDegrees))
    }
    return JSONObject().put("offset", offset).put("constituents", terms)
}
