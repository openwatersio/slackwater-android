package io.openwaters.slackwater.android.alerts

import android.os.Looper
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

class AlertDetailPlotDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accessiblePlotParksAndReopensAnEventWithOneMarkerForTwoReminders() {
        val station = StationIdentity(StationId("a"), "Hope Bay", "", 0.0, 0.0, StationKind.Tide)
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
            AlertDetailPlot(ready, ChartAlertSelection(station, ready.capabilities, AlertTrigger.TideExtreme(false),
                span.start, null), listOf(rule, rule.copy(id = java.util.UUID.randomUUID(), leadSeconds = 0)),
                0.5, repository, span.start, { edited = it }, heightUnits = HeightUnits.METRIC)
        }
        val marker = hasContentDescription("1 marked alert", substring = true)
        compose.waitUntil(5000) { compose.onAllNodes(marker).fetchSemanticsNodes().isNotEmpty() }
        val action = compose.onNode(marker).fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        compose.runOnIdle { assertTrue(action.action()) }
        compose.onNodeWithText("Alert set", substring = true).performClick()
        compose.runOnIdle { assertEquals(rule.id, edited?.rule?.id) }
        compose.onNodeWithText("Return to now").performClick()
        compose.onNodeWithText("Set an alert").assertExists()
    }
}
