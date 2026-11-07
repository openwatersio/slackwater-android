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
import io.openwaters.slackwater.iwls.IwlsStation
import io.openwaters.slackwater.iwls.IwlsTransport
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IwlsTidePreviewSource internal constructor(cacheDirectory: File, private val transport: IwlsTransport) {
    constructor(cacheDirectory: File) : this(cacheDirectory, HttpUrlConnectionTransport())

    private val stationsFile = File(cacheDirectory, "IwlsStations.json")
    private val client = IwlsClient(transport)

    suspend fun fetch(station: StationIdentity, now: Instant): List<TidePoint> = withContext(Dispatchers.IO) {
        if (station.kind != StationKind.CanadianTide) throw IwlsException.Permanent("Station is not a Canadian tide port")
        val iwls = resolveStation(station, now)
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

    private suspend fun resolveStation(station: StationIdentity, now: Instant): IwlsStation {
        fun resolve(list: List<IwlsStation>) =
            IwlsFetch.resolve(station.name, station.latitude, station.longitude, "wlp", list)
        val cached = try {
            if (stationsFile.isFile) resolve(IwlsFetch.decodeStations(stationsFile.readText())) else null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IwlsException.Permanent) {
            // IWLS may have added or moved a station since this list was written.
            null
        }
        val age = Duration.between(Instant.ofEpochMilli(stationsFile.lastModified()), now)
        if (cached != null && !age.isNegative && age < Duration.ofDays(7)) return cached
        var body: String? = null
        val stations = try {
            IwlsClient { url -> transport.get(url).also { body = it.body } }.stationList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: IwlsException) {
            return cached ?: throw failed
        }
        // Save only a decoded response. Concurrent readers always see a complete list.
        try {
            stationsFile.parentFile.mkdirs()
            val temp = File.createTempFile("IwlsStations", ".tmp", stationsFile.parentFile)
            try {
                temp.writeText(requireNotNull(body))
                temp.setLastModified(now.toEpochMilli())
                Files.move(temp.toPath(), stationsFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: IOException) {
            // The fetched list still resolves this station when cache storage is unavailable.
        }
        return resolve(stations)
    }
}
