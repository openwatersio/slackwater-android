package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.android.core.prediction.CurrentGateEvidence
import io.openwaters.slackwater.ChsTideFit
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.iwls.ChsSample
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IwlsTidePreviewSource internal constructor(cacheDirectory: File, transport: IwlsTransport) {
    constructor(cacheDirectory: File) : this(cacheDirectory, HttpUrlConnectionTransport())

    private val requestLock = Mutex()
    private val serial = IwlsTransport { url -> requestLock.withLock { transport.get(url) } }
    private val stations = IwlsStationDirectory(cacheDirectory, serial)
    private val client = IwlsClient(serial)
    private val weeks = TideFitWeekCache(File(cacheDirectory, "tide-fit-weeks"))

    private val currentWeeks = TideFitWeekCache(File(cacheDirectory, "current-fit-weeks"))

    fun stationZone(station: StationIdentity): ZoneId = try {
        station.timezone?.let(ZoneId::of) ?: ZoneId.systemDefault()
    } catch (_: DateTimeException) {
        ZoneId.systemDefault()
    }

    suspend fun fitChunks(station: StationIdentity, now: Instant, manual: Boolean): suspend (ChsChunk, IwlsSampleGrid) -> List<ChsSample> {
        require(station.kind == StationKind.CanadianTide)
        val iwls = stations.resolve(station, "wlp", now)
        return { chunk, grid ->
            weeks.load(iwls.id, chunk, grid, Instant.now(), manual) { client.series("wlp", iwls.id, chunk, grid) }
        }
    }

    suspend fun fitCurrent(
        station: StationIdentity,
        evidence: CurrentGateEvidence,
        now: Instant,
        manual: Boolean,
        publishProvisional: suspend (CanadianCurrentModel) -> Unit,
    ): CanadianCurrentModel = withContext(Dispatchers.IO) {
        require(station.kind == StationKind.CanadianCurrent &&
            evidence.allowsFinalFit(station.id))
        val iwls = stations.resolve(station, ChsCurrentFit.SPEED_SERIES, now)
        val metadata = client.metadata(iwls.id)
        val flood = metadata.floodDirection ?: throw IwlsException.Permanent("Missing flood direction for ${station.name}")
        val ebb = metadata.ebbDirection ?: throw IwlsException.Permanent("Missing ebb direction for ${station.name}")
        fitCanadianCurrentWindow(station, evidence, now, flood, ebb, { code, chunk, grid ->
            currentWeeks.load("$code/${iwls.id}", chunk, grid, Instant.now(), manual) {
                client.series(code, iwls.id, chunk, grid)
            }
        }, publishProvisional)
    }

    suspend fun fetch(station: StationIdentity, now: Instant): List<TidePoint> = withContext(Dispatchers.IO) {
        if (station.kind != StationKind.CanadianTide) throw IwlsException.Permanent("Station is not a Canadian tide port")
        val iwls = stations.resolve(station, "wlp", now)
        val midnight = IwlsFetch.localMidnight(now, stationZone(station))
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
