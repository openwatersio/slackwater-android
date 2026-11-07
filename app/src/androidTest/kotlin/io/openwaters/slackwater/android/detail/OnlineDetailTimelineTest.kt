package io.openwaters.slackwater.android.detail

import androidx.compose.ui.test.junit4.createComposeRule
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.core.timeline.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OnlineDetailTimelineTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("America/Vancouver")
    private val today = LocalDate.of(2026, 11, 1)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val station = StationIdentity(StationId("chs-test"), "Gate", "BC", 49.0, -123.0, StationKind.CanadianCurrent)
    private val series = PredictionSeries.Current(emptyList(), emptyList(), 20.0, 200.0)
    private val window = TimelineWindow.fixed(today, zone)
    private val next = today.plusWeeks(2)
    private val entered = CompletableDeferred<Unit>()
    private val finished = CompletableDeferred<Unit>()
    @Volatile private var saved = false
    private val repository = object : PredictionRepository {
        override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult =
            if (saved || span == TimeSpan(window.start, window.end))
                PredictionResult.Ready(id, span, "CHS", StationCapabilities.ONLINE_CURRENT, series)
            else PredictionResult.Unavailable(PredictionResult.Reason.OUTSIDE_COVERAGE)
    }
    private fun timeline() = DetailTimeline(TimelineController(now, zone, window, 360.0, TimelineMode.ONLINE,
        initialSelection = now, reduceMotion = true), null, repository, station, zone, series, emptyList())

    @Test fun nowSupersedesFetchAlreadyInFlight() = runBlocking {
        val timeline = timeline()
        timeline.dispatch(TimelineAction.PickDay(next))
        val task = launch {
            timeline.online(next, timeline.state.generation) {
                entered.complete(Unit)
                finished.await()
                saved = true
                true
            }
        }
        entered.await()
        timeline.dispatch(TimelineAction.Now(now))
        finished.complete(Unit)
        task.join()
        assertEquals(window, timeline.state.coverage)
        assertEquals(now, timeline.state.selected)
        assertNull(timeline.missingDay)
    }

    @Test fun dragAndReleaseShareOneFetchAndPublishOnlyAfterRelease() {
        val timeline = timeline()
        var calls = 0
        compose.setContent {
            OnlineTimelineRequests(timeline, available = true) {
                calls++
                entered.complete(Unit)
                finished.await()
                saved = true
                true
            }
        }
        compose.runOnIdle { timeline.dispatch(TimelineAction.PickDay(next)) }
        compose.waitUntil(10_000) { entered.isCompleted }
        compose.runOnIdle { timeline.dispatch(TimelineAction.BeginDrag) }
        compose.waitForIdle()
        compose.runOnIdle { timeline.dispatch(TimelineAction.PickDay(next)) }
        compose.waitForIdle()
        finished.complete(Unit)
        compose.waitUntil(10_000) { saved }
        compose.runOnIdle {
            assertEquals(1, calls)
            assertEquals(window, timeline.state.coverage)
            timeline.dispatch(TimelineAction.EndDrag(momentum = false))
        }
        compose.waitUntil(10_000) { timeline.state.coverage == TimelineWindow.fixed(next, zone) }
        compose.runOnIdle {
            assertEquals(1, calls)
            assertEquals(next, timeline.state.anchor)
            assertNull(timeline.missingDay)
        }
        val rebuilt = this.timeline()
        runBlocking { rebuilt.restorePosition(timeline.state) }
        assertEquals(timeline.state.coverage, rebuilt.state.coverage)
        assertEquals(timeline.state.selected, rebuilt.state.selected)
    }

    @Test fun offlineDateShowsMissingWeekAndNowRestoresCoveredStrip() = runBlocking {
        val timeline = timeline()
        timeline.dispatch(TimelineAction.PickDay(next))
        timeline.online(next, timeline.state.generation)
        assertEquals(next, timeline.missingDay)
        assertFalse(timeline.state.awaitingCoverage)
        timeline.dispatch(TimelineAction.Now(now))
        assertNull(timeline.missingDay)
        assertEquals(today, timeline.state.anchor)
    }
}
