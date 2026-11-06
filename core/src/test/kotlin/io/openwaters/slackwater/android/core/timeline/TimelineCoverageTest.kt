package io.openwaters.slackwater.android.core.timeline

import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.TidePoint
import kotlinx.coroutines.runBlocking
import io.openwaters.slackwater.android.core.prediction.PredictionRepository
import io.openwaters.slackwater.android.core.prediction.PredictionPurpose
import io.openwaters.slackwater.android.core.prediction.BoundedPredictions
import io.openwaters.slackwater.android.core.prediction.PredictionResult
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TimelineCoverageTest {
    private val start = Instant.parse("2026-03-07T08:00:00Z")
    private val station = StationId("fixture")
    private fun at(minutes: Long) = start.plusSeconds(minutes * 60)

    @Test fun onlineDetailCannotBridgeSeparateDownloadedBlocks() {
        val requested = TimelineWindow(start, at(228 * 60))
        val points = (0L..228 * 60 step 15).map { CurrentPoint(at(it), 1.0) }
        val span = TimeSpan(requested.start, requested.end)
        val full = BoundedPredictions.current(station, span, points, 0.0, 180.0)
        assertNotNull(full.onlineTimelineBlock(requested))
        val gap = BoundedPredictions.current(station, span, points.filter { it.time !in at(60)..at(120) }, 0.0, 180.0)
        assertTrue(gap is PredictionResult.Disjoint)
        assertNull(gap.onlineTimelineBlock(requested))
    }

    @Test fun finitePreviewIsAvailableOnlyWhileSamplesContainSessionNow() {
        val span = TimeSpan(start, at(60))
        val series = BoundedPredictions.tide(station, span, listOf(TidePoint(start, 1.0), TidePoint(at(30), 1.4), TidePoint(at(60), 1.1)))
        assertEquals(TimelineWindow(span.start, span.end), series.previewTimelineWindow(at(30)))
        assertNull(series.previewTimelineWindow(at(61)))
        assertNull(BoundedPredictions.tide(station, span, listOf(TidePoint(start, 1.0), TidePoint(at(60), 1.1))).previewTimelineWindow(at(30)))
    }

    @Test fun aPreviewSliceExpandsToTheEntireSavedSeriesBeforeItIsShown() = runBlocking {
        val points = (0L..120 step 15).map { TidePoint(at(it), it.toDouble()) }
        val source = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose) =
                BoundedPredictions.tide(id, span, points)
        }
        val slice = source.load(station, TimeSpan(at(45), at(60)), PredictionPurpose.DISPLAY) as PredictionResult.Ready
        assertNull(slice.previewTimelineWindow(at(45)))
        val full = requireNotNull(source.completeTimelinePreview(slice, at(45)))
        assertEquals(TimelineWindow(start, at(120)), full.previewTimelineWindow(at(45)))
        assertSame(full, source.completeTimelinePreview(full, at(45)))
    }
}
