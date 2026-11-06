package io.openwaters.slackwater.android.alerts

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.HeightUnits
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.test.platform.app.InstrumentationRegistry

class AlertDetailPlotDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accessiblePlotParksAndReopensAnEventWithOneMarkerForTwoReminders() {
        val station = StationIdentity(StationId("a"), "Hope Bay", "", 0.0, 0.0, StationKind.Tide, timezone = "America/Los_Angeles")
        val span = TimeSpan(Instant.EPOCH, Instant.EPOCH.plusSeconds(7200))
        val high = span.start.plusSeconds(3600)
        val ready = PredictionResult.Ready(station.id, span, "fixture", StationCapabilities.HARMONIC_TIDE,
            PredictionSeries.Tide(listOf(TidePoint(span.start, 0.0), TidePoint(high, 2.0), TidePoint(span.end, 0.0)),
                emptyList(), listOf(TideExtreme(high, 2.0, ExtremeKind.HIGH))))
        val rule = AlertRule.forEditor(station.id, AlertTrigger.TideExtreme(true), high)
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
                check(Looper.myLooper() != Looper.getMainLooper())
                return ready
            }
        }
        var edited: AlertEditorDraft? = null
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AlertDetailPlot(ready, ChartAlertSelection(station, ready.capabilities, AlertTrigger.TideExtreme(false),
                span.start, null), listOf(rule, rule.copy(id = java.util.UUID.randomUUID(), leadSeconds = 0)),
                0.5, repository, span.start, { edited = it }, heightUnits = HeightUnits.METRIC)
            }
        }
        val marker = hasContentDescription("1 marked alert", substring = true)
        compose.waitUntil(5000) { compose.onAllNodes(marker).fetchSemanticsNodes().isNotEmpty() }
        val plot = compose.onNode(hasContentDescription("Tide height", substring = true))
        fun assertCursor(moment: Instant) {
            assertTrue(plot.fetchSemanticsNode().config[SemanticsProperties.StateDescription].startsWith(station.timeLabel(moment)))
        }
        fun invokeAction(label: String) {
            val action = plot.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label.startsWith(label) }
            compose.runOnIdle { assertTrue(action.action()) }
        }
        compose.onNodeWithText("Show chart events").assertHeightIsAtLeast(48.dp).performClick()
        val locale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .withZone(ZoneId.of(station.timezone)).format(high)
        val highRow = compose.onNode(hasText(date, substring = true) and hasText("2.00 m", substring = true)
            and hasText("high tide", substring = true))
        highRow.assertExists()
        val startX = compose.onNodeWithText(station.timeLabel(span.start)).fetchSemanticsNode().boundsInRoot.left
        val endX = compose.onNodeWithText(station.timeLabel(span.end)).fetchSemanticsNode().boundsInRoot.left
        assertTrue(startX < endX)
        invokeAction("Set an alert")
        assertCursor(high)
        compose.onNodeWithText("Alert set", substring = true).performClick()
        compose.runOnIdle { assertEquals(rule.id, edited?.rule?.id); edited = null }
        compose.onNodeWithText("Return to now").performClick()
        invokeAction("Later by five minutes")
        assertCursor(span.start.plusSeconds(300))
        highRow.assertHeightIsAtLeast(48.dp).performClick()
        assertCursor(high)
        invokeAction("Later by five minutes")
        assertCursor(high.plusSeconds(300))
        compose.onNodeWithText("Alert set", substring = true).performClick()
        compose.runOnIdle {
            assertEquals(AlertTrigger.TideExtreme(true), edited?.rule?.trigger)
            assertEquals(high, edited?.boundMoment)
        }
        plot.performSemanticsAction(SemanticsActions.RequestFocus)
        plot.performKeyInput { pressKey(Key.DirectionRight) }
        assertCursor(high.plusSeconds(600))
        invokeAction("Previous event")
        assertCursor(high)
        invokeAction("Earlier by five minutes")
        invokeAction("Next event")
        compose.onNodeWithText("Alert set", substring = true).performClick()
        compose.runOnIdle { assertEquals(rule.id, edited?.rule?.id) }
        compose.onNodeWithText("Return to now").performClick()
        compose.onNodeWithText("Set an alert").assertExists()
    }

    @Test fun onlineCurrentSlackCanBeSelectedWithoutOfferingAnAlert() {
        val station = StationIdentity(StationId("current"), "Test current", "", 0.0, 0.0, StationKind.CanadianCurrent)
        val span = TimeSpan(Instant.EPOCH, Instant.EPOCH.plusSeconds(7200))
        val slack = span.start.plusSeconds(1800)
        val ready = PredictionResult.Ready(station.id, span, "fixture", StationCapabilities.ONLINE_CURRENT,
            PredictionSeries.Current(emptyList(), listOf(CurrentEvent(slack, 0.0, CurrentEventKind.SLACK)), null, null))
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult = ready
        }
        compose.setContent {
            AlertDetailPlot(ready, ChartAlertSelection(station, ready.capabilities, AlertTrigger.SlackWindowOpens,
                span.start, null), emptyList(), 0.5, repository, span.start, { fail("Unexpected alert editor") })
        }
        val plot = compose.onNode(hasContentDescription("Current speed", substring = true))
        val actions = plot.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertFalse(actions.any { it.label == "Set an alert" })
        val next = actions.single { it.label.startsWith("Next event") }
        assertTrue(next.label.contains("slack"))
        assertFalse(next.label.contains("window"))
        compose.runOnIdle { assertTrue(next.action()) }
        assertTrue(plot.fetchSemanticsNode().config[SemanticsProperties.StateDescription].startsWith(station.timeLabel(slack)))
        compose.onNodeWithText("Set an alert").assertDoesNotExist()
    }

}
