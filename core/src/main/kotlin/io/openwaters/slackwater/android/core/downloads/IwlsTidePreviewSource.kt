package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.ChsTideFit
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.android.core.prediction.BoundedPredictions
import io.openwaters.slackwater.android.core.prediction.PredictionResult
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.iwls.ChsChunk
import io.openwaters.slackwater.iwls.HttpUrlConnectionTransport
import io.openwaters.slackwater.iwls.IwlsClient
import io.openwaters.slackwater.iwls.IwlsException
import io.openwaters.slackwater.iwls.IwlsFetch
import io.openwaters.slackwater.iwls.IwlsSampleGrid
import io.openwaters.slackwater.iwls.IwlsTransport
import java.io.File
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IwlsTidePreviewSource internal constructor(cacheDirectory: File, transport: IwlsTransport) {
    constructor(cacheDirectory: File) : this(cacheDirectory, HttpUrlConnectionTransport())

    private val stations = IwlsStationDirectory(cacheDirectory, transport)
    private val client = IwlsClient(transport)

    suspend fun fetch(station: StationIdentity, now: Instant): List<TidePoint> = withContext(Dispatchers.IO) {
        if (station.kind != StationKind.CanadianTide) throw IwlsException.Permanent("Station is not a Canadian tide port")
        val iwls = stations.resolve(station, "wlp", now)
        val zone = try {
            station.timezone?.let(ZoneId::of) ?: ZoneId.systemDefault()
        } catch (_: DateTimeException) {
            ZoneId.systemDefault()
        }
        val midnight = IwlsFetch.localMidnight(now, zone)
        val start = midnight.minus(Duration.ofDays(2))
        val window = ChsChunk(start, start.plus(Duration.ofDays(7)))
        val points = client.series("wlp", iwls.id, window, IwlsSampleGrid(ChsTideFit.SAMPLE_MINUTES)).map {
            TidePoint(Instant.ofEpochMilli(it.tMillis), it.value)
        }
        val required = TimeSpan(now, midnight.plus(Duration.ofDays(2)))
        val preview = BoundedPredictions.tide(station.id, required, points)
        if (preview !is PredictionResult.Ready || preview.coverage != required) {
            throw IwlsException.Permanent("Incomplete tide preview")
        }
        points
    }
}
