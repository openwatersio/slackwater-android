package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.HarmonicSample
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.core.station.currentGateZone
import io.openwaters.slackwater.android.core.timeline.TimelineWindow
import io.openwaters.slackwater.iwls.HttpUrlConnectionTransport
import io.openwaters.slackwater.iwls.IwlsClient
import io.openwaters.slackwater.iwls.IwlsException
import io.openwaters.slackwater.iwls.IwlsFetch
import io.openwaters.slackwater.iwls.IwlsTransport
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FetchedOnlineGate(val points: List<CurrentPoint>, val floodDirection: Double, val ebbDirection: Double)

class IwlsOnlineGateSource internal constructor(cacheDirectory: File, transport: IwlsTransport) {
    constructor(cacheDirectory: File) : this(cacheDirectory, HttpUrlConnectionTransport())

    private val stations = IwlsStationDirectory(cacheDirectory, transport)
    private val client = IwlsClient(transport)

    suspend fun fetch(station: StationIdentity, anchor: Instant): FetchedOnlineGate = withContext(Dispatchers.IO) {
        if (station.kind != StationKind.CanadianCurrent) throw IwlsException.Permanent("Station is not a Canadian current gate")
        val zone = station.currentGateZone()
        val day = anchor.atZone(zone).toLocalDate()
        val start = TimelineWindow.fixed(day, zone).start
        val end = day.atStartOfDay(zone).toInstant().plus(Duration.ofDays(30))
        val iwls = stations.resolve(station, ChsCurrentFit.SPEED_SERIES, Instant.now())
        val metadata = client.metadata(iwls.id)
        val flood = metadata.floodDirection
        val ebb = metadata.ebbDirection
        if (flood == null || ebb == null || !flood.isFinite() || !ebb.isFinite()) {
            throw IwlsException.Permanent("${station.name}: IWLS metadata lacks flood or ebb direction")
        }
        val speeds = mutableListOf<HarmonicSample>()
        val directions = mutableListOf<HarmonicSample>()
        val days = Duration.between(start, end).toSeconds() / 86_400.0
        for (chunk in IwlsFetch.chunkPlan(days, end)) {
            suspend fun series(code: String) = client.series(code, iwls.id, chunk).map {
                HarmonicSample(Instant.ofEpochMilli(it.tMillis), it.value)
            }
            speeds += series(ChsCurrentFit.SPEED_SERIES)
            directions += series(ChsCurrentFit.DIRECTION_SERIES)
        }
        val points = ChsCurrentFit.project(speeds, directions, flood)
            .filter { it.time in start..end }
            .distinctBy { it.time }
            .sortedBy { it.time }
            .map { CurrentPoint(it.time, it.value) }
        if (points.isEmpty()) throw IwlsException.Permanent("${station.name}: IWLS returned no paired current samples")
        FetchedOnlineGate(points, flood, ebb)
    }
}
