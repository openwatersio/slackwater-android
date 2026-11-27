package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.ChsTideFit
import io.openwaters.slackwater.HarmonicFit
import io.openwaters.slackwater.HarmonicSample
import io.openwaters.slackwater.fit
import io.openwaters.slackwater.iwls.ChsChunk
import io.openwaters.slackwater.iwls.ChsSample
import io.openwaters.slackwater.iwls.IwlsException
import io.openwaters.slackwater.iwls.IwlsFetch
import io.openwaters.slackwater.iwls.IwlsSampleGrid
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fetch every planned chunk before fitting. Shared endpoints retain their IWLS sample weights. */
suspend fun fitCanadianTideWindow(
    now: Instant,
    stationZone: ZoneId,
    fetch: suspend (ChsChunk, IwlsSampleGrid) -> List<ChsSample>,
): HarmonicFit {
    val grid = IwlsSampleGrid(ChsTideFit.SAMPLE_MINUTES)
    val samples = mutableListOf<HarmonicSample>()
    val end = IwlsFetch.localMidnight(now, stationZone)
    for (chunk in IwlsFetch.chunkPlan(ChsTideFit.DAYS.toDouble(), end)) {
        currentCoroutineContext().ensureActive()
        val batch = fetch(chunk, grid)
        for (sample in batch) {
            val at = Instant.ofEpochMilli(sample.tMillis)
            if (at < chunk.start || at > chunk.end) throw IwlsException.Permanent("IWLS sample outside its tide window")
            samples.add(HarmonicSample(at, sample.value))
        }
    }
    return withContext(Dispatchers.Default) { fit(samples.sortedBy { it.time }, ChsTideFit.basis) }
}
