package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.android.core.downloads.fitCanadianTideWindow
import io.openwaters.slackwater.iwls.ChsChunk
import io.openwaters.slackwater.iwls.ChsSample
import io.openwaters.slackwater.iwls.IwlsSampleGrid
import java.time.Instant
import java.time.ZoneId
import io.openwaters.slackwater.android.core.station.StationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Publish only after the complete IWLS sample batch has fitted successfully. */
internal suspend fun ModelStore.fitCanadianTide(id: StationId, now: Instant, stationZone: ZoneId, fetch: suspend (ChsChunk, IwlsSampleGrid) -> List<ChsSample>) {
    val model = fitCanadianTideWindow(now, stationZone, fetch)
    val record = withContext(Dispatchers.Default) {
        val constituents = JSONArray()
        for (term in model.constituents) {
            constituents.put(JSONObject().put("name", term.name)
                .put("amplitude", term.amplitude).put("phase", term.phaseDegrees))
        }
        JSONObject().put("offset", model.offset).put("constituents", constituents)
    }
    write(id, ModelKind.TIDE, record)
}
