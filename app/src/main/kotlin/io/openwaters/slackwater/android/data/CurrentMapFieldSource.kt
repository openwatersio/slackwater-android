package io.openwaters.slackwater.android.data

import android.content.Context
import io.openwaters.slackwater.android.core.map.FillField
import io.openwaters.slackwater.android.core.map.PatchField
import io.openwaters.slackwater.android.core.map.doddMapVector
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.map.CurrentMapFields
import io.openwaters.slackwater.speedAt
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CurrentMapFieldSource(
    context: Context,
    private val currentAt: suspend (StationId, Instant) -> PredictionResult = AndroidPredictionRepository(context)::currentAt,
) {
    private val assets = context.applicationContext.assets
    private val catalog = BundledStationCatalog(assets)
    private val fill by lazy { load("fill-salish", FillField::decode) }
    private val patches by lazy { load("patches-salish", PatchField::decode) }

    private fun <T> load(name: String, decode: (ByteArray, String) -> T?): T? = try {
        val header = assets.open("slackwater/$name.json").bufferedReader().use { it.readText() }
        val binary = assets.open("slackwater/$name.bin").use { it.readBytes() }
        decode(binary, header)
    } catch (_: IOException) {
        null
    }

    suspend fun evaluate(at: Instant): CurrentMapFields = withContext(Dispatchers.Default) {
        val field = patches
        val anchors = field?.anchors.orEmpty().associateWith { anchor ->
            when (anchor.provider) {
                "noaa" -> StationId("current:noaa/${anchor.stationId}")
                "chs" -> StationId(anchor.stationId)
                else -> null
            }
        }
        val dodd = StationId("chs-dodd-narrows")
        val readings = (anchors.values.filterNotNull().toSet() + dodd).associateWith { id ->
            val result = currentAt(id, at)
            val ready = when (result) {
                is PredictionResult.Ready -> result
                is PredictionResult.Disjoint -> result.blocks.firstOrNull { at in it.coverage }
                is PredictionResult.Unavailable -> null
            }
            ready?.takeIf { it.station == id && at in it.coverage }?.series as? PredictionSeries.Current
        }
        val speeds = anchors.mapValues { (_, id) -> readings[id]?.points?.speedAt(at) }
        val direction = readings[dodd]?.let { current ->
            doddMapVector(current.points.speedAt(at), current.floodBearing, current.ebbBearing, at)?.directionDegrees
        }
        val doddArrow = direction?.let { bearing ->
            (catalog.resolve(dodd) as? StationResolution.Live)?.identity?.coordinate?.let { it to bearing }
        }
        CurrentMapFields(fill?.cells(at).orEmpty(), field?.cells { speeds[it] }.orEmpty(), doddArrow)
    }
}
