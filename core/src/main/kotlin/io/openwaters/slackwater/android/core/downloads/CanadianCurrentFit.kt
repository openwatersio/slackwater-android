package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.HarmonicFit
import io.openwaters.slackwater.HarmonicSample
import io.openwaters.slackwater.android.core.prediction.CurrentGateEvidence
import io.openwaters.slackwater.android.core.prediction.StationCapabilities
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.core.station.currentGateZone
import io.openwaters.slackwater.fit
import io.openwaters.slackwater.iwls.ChsChunk
import io.openwaters.slackwater.iwls.ChsSample
import io.openwaters.slackwater.iwls.IwlsException
import io.openwaters.slackwater.iwls.IwlsFetch
import io.openwaters.slackwater.iwls.IwlsSampleGrid
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class CanadianCurrentModel(
    val station: StationId,
    val fitDays: Double,
    val floodDirection: Double,
    val ebbDirection: Double,
    val harmonics: HarmonicFit,
) {
    val recipe: String get() = ChsCurrentFit.STAMP
}

suspend fun fitCanadianCurrentWindow(
    station: StationIdentity,
    evidence: CurrentGateEvidence,
    now: Instant,
    floodDirection: Double,
    ebbDirection: Double,
    fetch: suspend (String, ChsChunk, IwlsSampleGrid) -> List<ChsSample>,
    publishProvisional: suspend (CanadianCurrentModel) -> Unit,
): CanadianCurrentModel {
    require(station.kind == StationKind.CanadianCurrent &&
        evidence.allowsFinalFit(station.id))
    if (!floodDirection.isFinite() || !ebbDirection.isFinite()) throw IwlsException.Permanent("Missing current axis")
    val end = IwlsFetch.localMidnight(now, station.currentGateZone())
    val shortDays = ChsCurrentFit.days.first()
    val cutoff = end.minus(Duration.ofDays(shortDays.toLong()))
    var provisional = evidence.compatibleFit(station.id.value, ChsCurrentFit.STAMP, shortDays.toDouble())
        ?.capability == StationCapabilities.PROVISIONAL_CURRENT
    val speeds = mutableListOf<HarmonicSample>()
    val directions = mutableListOf<HarmonicSample>()
    val grid = IwlsSampleGrid(ChsCurrentFit.SAMPLE_MINUTES)
    suspend fun model(days: Double) = withContext(Dispatchers.Default) {
        val projected = ChsCurrentFit.project(speeds.sortedBy { it.time }, directions, floodDirection)
        if (projected.isEmpty()) throw IwlsException.Permanent("No paired current samples for ${station.name}")
        CanadianCurrentModel(station.id, days, floodDirection, ebbDirection, fit(projected, ChsCurrentFit.basis))
    }
    for (chunk in IwlsFetch.chunkPlan(evidence.fitDays, end)) {
        suspend fun collectSeries(code: String, target: MutableList<HarmonicSample>) {
            currentCoroutineContext().ensureActive()
            for (sample in fetch(code, chunk, grid)) {
                val at = Instant.ofEpochMilli(sample.tMillis)
                if (at < chunk.start || at > chunk.end) throw IwlsException.Permanent("IWLS sample outside current window")
                target.add(HarmonicSample(at, sample.value))
            }
        }
        collectSeries(ChsCurrentFit.SPEED_SERIES, speeds)
        collectSeries(ChsCurrentFit.DIRECTION_SERIES, directions)
        if (provisional && chunk.start <= cutoff) {
            val early = model(shortDays.toDouble())
            currentCoroutineContext().ensureActive()
            publishProvisional(early)
            provisional = false
        }
    }
    return model(evidence.fitDays)
}
