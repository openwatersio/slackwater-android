package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.TideRatePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.core.timeline.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScrubberConformanceTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-11-01T09:30:00Z")
    private val zone = ZoneId.of("America/Vancouver")
    private val station = StationIdentity(StationId("a"), "Active Pass", "", 48.0, -123.0, StationKind.Current)
    private val window = TimelineWindow.fixed(now.atZone(zone).toLocalDate(), zone)
    private lateinit var timeline: DetailTimeline
    private var parked: Instant? = null
    private var editor: Instant? = null
    private var pageOffset = 0
    private val strip get() = compose.onNodeWithTag("detailTimeline")

    private fun mount(mode: TimelineMode = TimelineMode.CONTINUOUS, opening: Boolean = false) {
        val controller = TimelineController(now, zone, window, 0.0, mode, reduceMotion = !opening)
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult =
                error("The fixture has all its data")
        }
        timeline = DetailTimeline(controller, null, repository, station, zone,
            PredictionSeries.Current(emptyList(), emptyList(), null, null), emptyList())
        timeline.dispatch(TimelineAction.Targets(MagneticTargets(listOf(now.plusSeconds(3600)))))
        compose.setContent {
            val scroll = rememberScrollState()
            val offset = scroll.value
            SideEffect { pageOffset = offset }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Box(Modifier.width(320.dp).height(240.dp).detailTimelineInput(timeline,
                        timeline.state.selected.toString(), onPress = { parked = it }, onAlert = { editor = it }))
                    Spacer(Modifier.height(1600.dp))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun action(label: String): Boolean {
        val action = strip.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        return compose.runOnIdle { action.action() }
    }

    @Test fun adjustableValueAndKeyboardUseFiveMinuteStepsInRtlAndExactEventTargets() {
        mount()
        assertTrue(action("Later by five minutes"))
        assertEquals(now.plusSeconds(300), timeline.state.selected)
        strip.performSemanticsAction(SemanticsActions.RequestFocus)
        strip.performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(now, timeline.state.selected)
        assertTrue(action("Next event"))
        assertEquals(now.plusSeconds(3600), timeline.state.selected)
        assertFalse(action("Next event"))
        assertTrue(action("Set an alert"))
        assertEquals(timeline.state.selected, editor)
        assertNull(parked)
    }

    @Test fun interruptedOpeningWaitsForReleaseAndLongPressParksWithoutAlsoTapping() {
        mount(opening = true)
        assertEquals(TimelineMotion.OPENING, timeline.state.motion)
        strip.performTouchInput { down(center) }
        assertEquals(TimelineMotion.OPENING, timeline.state.motion)
        strip.performTouchInput { advanceEventTime(80); up() }
        assertEquals(TimelineMotion.RESTING, timeline.state.motion)
        strip.performTouchInput { longClick(Offset(width * .65f, height * .4f)) }
        assertNotNull(parked)
        assertEquals(parked, timeline.state.selected)
        assertNull(editor)
    }

    @Test fun reduceMotionHorizontalDragAdvancesTimeAndVerticalGestureScrollsPage() {
        mount()
        assertEquals(now, timeline.state.selected)
        strip.performTouchInput { swipe(Offset(width * .8f, centerY), Offset(width * .25f, centerY), 450) }
        compose.waitForIdle()
        assertTrue(timeline.state.selected > now)
        val selected = timeline.state.selected
        strip.performTouchInput { swipe(Offset(centerX, height * .85f), Offset(centerX, height * .2f), 450) }
        compose.waitForIdle()
        assertTrue(pageOffset > 0)
        assertEquals(selected, timeline.state.selected)
    }

    @Test fun modelReplacementRebuildsCoverageAroundTheLatestSettledMoment() = runBlocking {
        val tide = station.copy(kind = StationKind.CanadianTide, latitude = Double.NaN)
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult.Ready {
                val times = generateSequence(span.start) { it.plusSeconds(600) }.takeWhile { it <= span.end }.toList()
                return PredictionResult.Ready(id, span, "fixture", StationCapabilities.FITTED_TIDE,
                    PredictionSeries.Tide(times.map { TidePoint(it, 2.0) }, times.map { TideRatePoint(it, 0.0) }, emptyList()))
            }
        }
        val first = repository.load(tide.id, TimeSpan(now, now), PredictionPurpose.DISPLAY)
        val replacement = requireNotNull(openDetailTimeline(repository, tide, zone, now, first, false, now))
        try {
            val destination = now.plusSeconds(40L * 86400)
            val anchor = destination.atZone(zone).toLocalDate().minusDays(2)
            val saved = replacement.state.copy(selected = destination, viewport = 900.0, anchor = anchor)
            withContext(Dispatchers.Main) { replacement.restorePosition(saved) }
            assertEquals(destination, replacement.state.selected)
            assertEquals(anchor, replacement.state.anchor)
            assertEquals(TimelineMotion.RESTING, replacement.state.motion)
            assertEquals(destination, replacement.state.coverage.selected(replacement.state.offset, 900.0))
            val samples = (replacement.series as PredictionSeries.Tide).points
            assertTrue(samples.first().time < destination && samples.last().time > destination)
        } finally { replacement.close() }
    }

    @Test fun previewStopsAtHardDataEdgesAndHasNoAlertActionOrPressHandoff() {
        mount(mode = TimelineMode.PREVIEW)
        compose.runOnIdle { timeline.direct(window.end) }
        assertEquals(window.clamp(window.end, timeline.state.viewport), timeline.state.selected)
        assertFalse(action("Later by five minutes"))
        assertFalse(strip.fetchSemanticsNode().config[SemanticsActions.CustomActions].any { it.label == "Set an alert" })
        strip.performTouchInput { longClick(center) }
        assertNull(parked)
    }
}
