package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.openwaters.slackwater.CurrentEvent
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.core.timeline.*
import io.openwaters.slackwater.android.data.ReadingPreferences
import java.time.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScheduleConformanceTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("America/Vancouver")
    private val day = LocalDate.of(2026, 11, 1)
    private val first = Instant.parse("2026-11-01T08:30:00Z")
    private val repeat = first.plusSeconds(3600)
    private val tomorrow = day.plusDays(1).atTime(8, 0).atZone(zone).toInstant()
    private val station = StationIdentity(StationId("fixture"), "Fixture", "", 49.0, -123.0, StationKind.CanadianCurrent, timezone = zone.id)
    private val events = listOf(CurrentEvent(first, 0.0, CurrentEventKind.SLACK), CurrentEvent(repeat, 2.0, CurrentEventKind.MAX_FLOOD),
        CurrentEvent(tomorrow, 0.0, CurrentEventKind.SLACK))
    private val series = PredictionSeries.Current(emptyList(), events, 60.0, 240.0)
    private val repository = object : PredictionRepository {
        override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose) =
            PredictionResult.Ready(id, span, "CHS", StationCapabilities.ONLINE_CURRENT, series)
    }

    @Test fun rowTapParksOnTheExactInstantAndOnlyOneDayOpensAcrossTheFallClockChange() {
        val controller = TimelineController(first, zone, TimelineWindow.fixed(day, zone), 40.0, TimelineMode.ONLINE,
            initialSelection = first, reduceMotion = true)
        val timeline = DetailTimeline(controller, null, repository, station, zone, series, emptyList())
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DetailSchedule(station, timeline, ReadingPreferences(), repository, 0) { }
            }
        }
        fun row(t: Instant) = compose.onNodeWithTag("schedule-event-$t")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("schedule-event-$first").fetchSemanticsNodes().isNotEmpty() }
        row(first).assertIsSelected()
        row(repeat).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(repeat, timeline.state.selected) }
        row(repeat).assertIsSelected()
        compose.onNodeWithTag("schedule-day-${day.plusDays(1)}").performScrollTo().performClick()
        row(tomorrow).assertExists()
        row(first).assertDoesNotExist()
        compose.onNodeWithTag("schedule-day-${day.plusDays(1)}").performClick()
        row(tomorrow).assertDoesNotExist()
    }

    @Test fun previewScheduleStartsOnSessionTodayAndShowsOnlyDownloadedTurnsWithoutDateNavigation() {
        val tide = station.copy(kind = StationKind.CanadianTide)
        val start = day.atStartOfDay(zone).toInstant().minusSeconds(1800)
        val samples = (0..12).map { index -> TidePoint(start.plusSeconds(index * 900L), if (index % 2 == 0) 1.0 else 2.0) }
        val saved = TimeSpan(samples.first().time, samples.last().time)
        val preview = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose) = BoundedPredictions.tide(id, span, samples)
        }
        val data = BoundedPredictions.tide(tide.id, saved, samples) as PredictionResult.Ready
        val controller = TimelineController(first, zone, TimelineWindow(saved.start, saved.end), 0.0, TimelineMode.PREVIEW,
            initialSelection = first, reduceMotion = true, anchor = day.minusDays(1))
        val timeline = DetailTimeline(controller, null, preview, tide, zone, data.series, emptyList())
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DetailSchedule(tide, timeline, ReadingPreferences(), preview, 0) { error("Preview offered a date picker") }
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("printable-tables-row").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithTag("schedule-event-${start.plusSeconds(2700)}").assertExists()
        compose.onNodeWithTag("schedule-event-${start.plusSeconds(900)}").assertDoesNotExist()
        compose.onNodeWithTag("schedule-day-${day.plusDays(1)}").assertDoesNotExist()
        compose.onNodeWithTag("schedule-week").assertDoesNotExist()
        compose.onNodeWithText("Previous week").assertDoesNotExist()
        compose.onNodeWithText("Next week").assertDoesNotExist()
    }

}
