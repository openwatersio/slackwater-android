package io.openwaters.slackwater.android.alerts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.data.ModelStore
import io.openwaters.slackwater.android.data.ModelKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class AlertEditorTest {
    @get:Rule val compose = createComposeRule()
    private val station = StationIdentity(StationId("a"), "Hope Bay", "", 0.0, 0.0, StationKind.Tide)
    private val trigger = AlertTrigger.TideExtreme(false)
    private val date = Instant.now().plusSeconds(86400)
    private val selection = ChartAlertSelection(station, StationCapabilities.HARMONIC_TIDE, trigger, date, date)

    @Test fun freePreviewCanChangeOptionsAndOnlySaveRequestsPremium() {
        var upsells = 0
        compose.setContent {
            MaterialTheme {
                AlertEditor(AlertEditorDraft(AlertRule.forEditor(station.id, trigger, date), station, date), false,
                    onSave = { fail("Free preview wrote a rule") }, onDelete = {}, onPremium = { upsells++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("30 minutes before").performClick()
        compose.onNodeWithText("1 day before").performClick()
        compose.onNodeWithText("Does not repeat").performClick()
        compose.onNodeWithText("Every low tide").performClick()
        compose.runOnIdle { assertEquals(0, upsells) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(1, upsells) }
    }

    @Test fun aPastOnceCanOnlyBeSavedAfterChangingToEvery() {
        val past = Instant.EPOCH
        var saved: AlertRule? = null
        compose.setContent {
            AlertEditor(AlertEditorDraft(AlertRule.forEditor(station.id, trigger, past), station, past), true,
                onSave = { saved = it }, onDelete = {}, onPremium = {}, onDismiss = {})
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Does not repeat").performClick()
        compose.onNodeWithText("Every low tide").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertNull(requireNotNull(saved).once) }
    }

    @Test fun chartAndSavedRuleEditorsShowTheCurrentMeasuredSlackBound() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "alert-bound-${UUID.randomUUID()}")
        val models = ModelStore(directory)
        val gate = station.copy(id = StationId("chs-johnstone-strait-central"), name = "Johnstone Strait", kind = StationKind.CanadianCurrent)
        val rule = AlertRule.forEditor(gate.id, AlertTrigger.Slack, date)
        val chart = ChartAlertSelection(gate, StationCapabilities.PROVISIONAL_CURRENT, rule.trigger, date, date)
        val fit = JSONObject("""{"offset":1,"fitDays":60,"floodDirection":20,"ebbDirection":215,
            "constituents":[{"name":"M2","amplitude":1,"phase":10}]}""").put("recipe", ChsCurrentFit.STAMP)
        val warning = context.getString(R.string.detail_bound, gate.name, 35)
        try {
            runBlocking { models.write(gate.id, ModelKind.CURRENT, fit) }
            compose.setContent {
                var draft by remember { mutableStateOf<AlertEditorDraft?>(null) }
                Column {
                    AlertLine(chart, false, emptyList(), Instant.now()) { draft = it }
                    AlertsScreen(listOf(AlertRow(rule, gate, "Scheduled")), onEdit = { draft = it }, onDismiss = {})
                }
                draft?.let {
                    AlertEditor(it, true, onSave = {}, onDelete = {}, onPremium = {}, onDismiss = { draft = null }, models = models)
                }
            }
            compose.onNodeWithText("Set an alert").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Scheduled").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { models.write(gate.id, ModelKind.CURRENT, fit.put("fitDays", 210)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty() }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun halfSecondParksTheMomentAndOnlyTheLineTapOpensTheEditor() {
        var edits = 0
        compose.setContent {
            var parked by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.Column {
                Box(Modifier.size(100.dp).testTag("plot").chartAlertPress(true, { selection }, selection) { parked = true })
                AlertLine(selection, parked, emptyList(), Instant.EPOCH) { edits++ }
            }
        }
        compose.mainClock.autoAdvance = false
        val plot = compose.onNodeWithTag("plot")
        plot.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Set an alert").assertExists()
        compose.mainClock.advanceTimeBy(180)
        plot.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, edits) }
        compose.onNodeWithText("Set alert for low tide", substring = true).performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }
}
