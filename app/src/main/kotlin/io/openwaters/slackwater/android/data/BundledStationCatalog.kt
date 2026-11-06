package io.openwaters.slackwater.android.data

import android.content.res.AssetManager
import io.openwaters.slackwater.android.core.station.StationCatalog
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentityIndex
import io.openwaters.slackwater.android.core.station.StationResolution
import io.openwaters.slackwater.android.core.station.StationSuccessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BundledStationCatalog(private val assets: AssetManager) : StationCatalog {
    private val index by lazy { load() }

    override suspend fun identities(): List<StationIdentity> = withContext(Dispatchers.IO) { index.identities }

    override suspend fun resolve(id: StationId): StationResolution = withContext(Dispatchers.IO) { index.resolve(id) }

    private fun read(name: String): String =
        assets.open("slackwater/$name.json").bufferedReader().use { it.readText() }

    private fun load(): StationIdentityIndex {
        val index = JSONObject(read("station-index"))
        val identities = buildList {
            fun addRows(rows: JSONArray, kind: StationKind) {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val rawId = row.getString("id")
                    add(StationIdentity(
                        id = StationId(if (kind == StationKind.Current) "current:$rawId" else rawId),
                        name = row.getString("name"),
                        region = row.getString("region"),
                        latitude = row.getDouble("latitude"),
                        longitude = row.getDouble("longitude"),
                        kind = kind,
                        aliases = row.optJSONArray("aliases")?.let { aliases ->
                            List(aliases.length()) { aliases.getString(it) }
                        }.orEmpty(),
                        timezone = if (row.has("timezone")) row.getString("timezone") else null,
                        source = if (kind == StationKind.Restricted) row.getString("source") else null,
                        license = if (kind == StationKind.Restricted) row.getString("license") else null,
                    ))
                }
            }

            addRows(JSONArray(read("chs-tombstones")), StationKind.Retired)
            addRows(JSONArray(read("station-tombstones")), StationKind.Retired)
            addRows(JSONArray(read("unavailable-stations")), StationKind.Restricted)
            addRows(index.getJSONArray("tides"), StationKind.Tide)
            addRows(index.getJSONArray("currents"), StationKind.Current)
            addRows(JSONArray(read("chs-stations")), StationKind.CanadianTide)
            addRows(JSONArray(read("chs-gates")), StationKind.DerivedGate)
            addRows(JSONArray(read("chs-current-gates")), StationKind.CanadianCurrent)
        }
        val entries = JSONObject(read("station-successors"))
        val successors = entries.keys().asSequence().associate { old ->
            val entry = entries.getJSONObject(old)
            StationId(old) to StationSuccessor(
                StationId(entry.getString("id")),
                if (entry.isNull("datumShiftM")) null else entry.getDouble("datumShiftM"),
            )
        }
        return StationIdentityIndex(identities, successors)
    }
}
