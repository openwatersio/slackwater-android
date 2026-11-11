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

    @Test fun onlyAlignedWeeksWithBothExactEndpointsAreReusable() = runBlocking {
        val cache = TideFitWeekCache(folder.root)
        val grid = IwlsSampleGrid(15)
        val week = 604800L
        val now = Instant.EPOCH.plusSeconds(week * 3)
        val first = ChsChunk(Instant.EPOCH, Instant.EPOCH.plusSeconds(week))
        val second = ChsChunk(first.end, first.end.plusSeconds(week))
        fun endpoints(chunk: ChsChunk) = listOf(ChsSample(chunk.start.toEpochMilli(), 1.0),
            ChsSample(chunk.end.toEpochMilli(), 2.0))
        for (chunk in listOf(first, second)) {
            val complete = endpoints(chunk)
            val lateStart = complete.first().copy(tMillis = chunk.start.plusSeconds(900).toEpochMilli())
            val earlyEnd = complete.last().copy(tMillis = chunk.end.minusSeconds(900).toEpochMilli())
            for (incomplete in listOf(listOf(lateStart, complete.last()), listOf(complete.first(), earlyEnd))) {
                assertEquals(incomplete, cache.load("port", chunk, grid, now) { incomplete })
            }
            assertEquals(complete, cache.load("port", chunk, grid, now) { complete })
        }
        val reopened = TideFitWeekCache(folder.root)
        for (chunk in listOf(first, second)) {
            assertEquals(endpoints(chunk), reopened.load("port", chunk, grid, now) { error("missing saved week") })
        }
        val shifted = ChsChunk(first.start.plusSeconds(900), first.end.plusSeconds(900))
        val partial = ChsChunk(first.start, first.end.minusSeconds(900))
        for (chunk in listOf(shifted, partial)) {
            assertEquals(endpoints(chunk), cache.load("port", chunk, grid, now) { endpoints(chunk) })
            assertEquals(emptyList<ChsSample>(), cache.load("port", chunk, grid, now) { emptyList() })
        }
    }
}
