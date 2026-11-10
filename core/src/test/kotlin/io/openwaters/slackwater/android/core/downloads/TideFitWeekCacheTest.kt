package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.iwls.ChsChunk
import io.openwaters.slackwater.iwls.ChsSample
import io.openwaters.slackwater.iwls.IwlsSampleGrid
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TideFitWeekCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun emptyWeeksExpireAndCompleteWeeksReopenWithDuplicateWeights() = runBlocking {
        val chunk = ChsChunk(Instant.EPOCH, Instant.EPOCH.plusSeconds(604800))
        val cache = TideFitWeekCache(folder.root)
        val grid = IwlsSampleGrid(15)
        val now = chunk.end
        val samples = listOf(ChsSample(0, 1.0), ChsSample(0, 1.5), ChsSample(chunk.end.toEpochMilli(), 2.0))
        assertEquals(emptyList<ChsSample>(), cache.load("port", chunk, grid, now) { emptyList() })
        assertEquals(emptyList<ChsSample>(), cache.load("port", chunk, grid, now.plusSeconds(86399)) { error("early retry") })
        assertEquals(samples, cache.load("port", chunk, grid, now.plusSeconds(86400)) { samples })
        assertEquals(samples, TideFitWeekCache(folder.root).load("port", chunk, grid, now.plusSeconds(172800)) { error("refetch") })
        cache.load("manual", chunk, grid, now) { emptyList() }
        assertEquals(samples, cache.load("manual", chunk, grid, now, bypassEmpty = true) { samples })
        assertEquals(samples, cache.load("manual", chunk, grid, now, bypassEmpty = true) { error("positive retry") })
        for ((id, otherGrid) in listOf("other" to grid, "port" to IwlsSampleGrid(60))) {
            assertEquals(samples.take(1), cache.load(id, chunk, otherGrid, now) { samples.take(1) })
            assertEquals(samples, cache.load(id, chunk, otherGrid, now) { samples })
        }
    }
}
