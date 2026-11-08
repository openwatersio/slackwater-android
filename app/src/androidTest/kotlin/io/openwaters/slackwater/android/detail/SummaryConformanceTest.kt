package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.openwaters.slackwater.*
import io.openwaters.almanac.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.core.timeline.*
import io.openwaters.slackwater.android.data.ReadingPreferences
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SummaryConformanceTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-11-06T18:00:00Z")
    private val zone = ZoneId.of("America/Vancouver")
    private val date = now.atZone(zone).toLocalDate()
    private val window = TimelineWindow(now.minus(Duration.ofDays(2)), now.plus(Duration.ofDays(40)))
    private val prefs = ReadingPreferences()
    private lateinit var timeline: DetailTimeline

    private fun mount(series: PredictionSeries, kind: StationKind, mode: TimelineMode, provisional: Boolean = false) {
        val station = StationIdentity(StationId("fixture"), "Fixture Pass", "", 49.1, -123.2, kind, timezone = zone.id)
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose) =
                PredictionResult.Ready(id, span, "fixture", StationCapabilities.HARMONIC_CURRENT, series)
        }
        timeline = DetailTimeline(TimelineController(now, zone, window, 40.0, mode, initialSelection = now,
            reduceMotion = true), null, repository, station, zone, series, listOf(TimelineDay(date,
            sunrise = now.minusSeconds(7200), sunset = now.plusSeconds(21600))))
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DetailSummary(station, timeline, prefs, repository, "Point Atkinson", provisional)
            }
        }
    }

    @Test fun derivedGateShowsItsReferencePortAndNoSpeedOrRangeSheet() {
        mount(PredictionSeries.Derived(emptyList()), StationKind.DerivedGate, TimelineMode.CONTINUOUS)
        compose.onNodeWithText("No speed").assertExists()
        compose.onNodeWithText("Point Atkinson tides").assertExists()
        compose.onNodeWithText("Shape only").assert(hasNoClickAction())
    }

    @Test fun anOnlineGateStaysInertAndASunEventJumpsToItsExactInstant() {
        val series = PredictionSeries.Current(emptyList(), listOf(CurrentEvent(now.plusSeconds(3600), 2.0, CurrentEventKind.MAX_FLOOD)), 90.0, 270.0)
        mount(series, StationKind.CanadianCurrent, TimelineMode.ONLINE)
        compose.onNodeWithText("Next max").assert(hasNoClickAction())
        compose.onNodeWithText("Sun").performScrollTo().performClick()
        val next = compose.onAllNodes(hasText("Sunset ·", substring = true) and hasClickAction()).onFirst()
        next.performScrollTo().performClick()
        compose.runOnIdle { assertEquals(now.plusSeconds(21600), timeline.state.selected) }
    }

    @Test fun aMarkedProvisionalMaximumKeepsItsDirectionTimeAndApproximation() {
        val series = PredictionSeries.Current(emptyList(), listOf(
            CurrentEvent(now.minusSeconds(7200), 1.0, CurrentEventKind.MAX_FLOOD),
            CurrentEvent(now.minusSeconds(3600), -6.0, CurrentEventKind.MAX_EBB),
            CurrentEvent(now.plusSeconds(3600), 2.0, CurrentEventKind.MAX_FLOOD)), 90.0, 270.0)
        mount(series, StationKind.CanadianCurrent, TimelineMode.CONTINUOUS, true)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Hardest", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("~2.0 kn").assertExists()
        compose.onNodeWithText("Next max").performClick()
        compose.onNodeWithText("Is this beyond normal?").assertExists()
    }
    @Test fun ordinaryRangeOpensAndNextBiggerSwingMovesTheSelectionExactly() {
        val times = List(15) { now.plusSeconds((it - 7L) * 21600 - 60) }
        val events = times.mapIndexed { index, time ->
            val height = if (index % 2 == 0) 0.0 else if (index > 8) 4.0 else 1.0
            TideExtreme(time, height, if (index % 2 == 0) ExtremeKind.LOW else ExtremeKind.HIGH)
        }
        mount(PredictionSeries.Tide(emptyList(), emptyList(), events), StationKind.CanadianTide, TimelineMode.CONTINUOUS)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Range") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Range").performClick()
        compose.onNodeWithText("Is this beyond normal?").assertExists()
        compose.onNodeWithText("Against this station’s own ends").assertDoesNotExist()
        compose.onNodeWithText("Bigger than all but", substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(times[9], timeline.state.selected) }
    }

    @Test fun moonJumpClosesTheSheetAndGoesToTheNightOfTheNextFullMoon() {
        mount(PredictionSeries.Derived(emptyList()), StationKind.DerivedGate, TimelineMode.CONTINUOUS)
        compose.onNodeWithText("Moon").performClick()
        val matcher = hasText("Next full Moon", substring = true) and hasClickAction()
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val target = compose.onNode(matcher)
        target.performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
        val full = searchMoonPhases(now, now.plus(Duration.ofDays(32))).first { it.phase == MoonPhaseName.FULL }
        compose.runOnIdle {
            val requested = timeline.state.move?.to ?: timeline.state.selected
            assertTrue(Duration.between(full.time, requested).abs() <= Duration.ofDays(1))
        }
    }

}
