package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.iwls.IwlsClient
import io.openwaters.slackwater.iwls.IwlsException
import io.openwaters.slackwater.iwls.IwlsFetch
import io.openwaters.slackwater.iwls.IwlsStation
import io.openwaters.slackwater.iwls.IwlsTransport
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal class IwlsStationDirectory(cacheDirectory: File, private val transport: IwlsTransport) {
    private val stationsFile = File(cacheDirectory, "IwlsStations.json")

    suspend fun resolve(station: StationIdentity, series: String, now: Instant): IwlsStation {
        fun resolve(list: List<IwlsStation>) =
            IwlsFetch.resolve(station.name, station.latitude, station.longitude, series, list)
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
