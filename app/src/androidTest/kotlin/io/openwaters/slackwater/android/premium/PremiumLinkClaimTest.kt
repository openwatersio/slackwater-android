package io.openwaters.slackwater.android.premium

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.BuildConfig
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.NEXT_HIGH_SEARCH
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.AlertRule
import io.openwaters.slackwater.android.core.planning.AlertTrigger
import io.openwaters.slackwater.android.core.planning.OccurrenceResolver
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.AlertRuleStore
import io.openwaters.slackwater.android.data.AndroidPredictionRepository
import io.openwaters.slackwater.android.data.DeliveryPlanStore
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PremiumLinkClaimTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun claimingThroughTheLinkRebuildsARetainedReminderWithoutOpeningMainActivity() {
        assumeTrue(BuildConfig.PREMIUM_ENABLED)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val access = ReminderAccessStore.get(app)
        val original = runBlocking { access.freeDayRecord.first() }
        assertTrue("Expected readable test preferences", original.historyReadable)
        val rules = AlertRuleStore.get(app)
        // Highs here can fall more than a day apart (NEXT_HIGH_SEARCH), so no fixed lead keeps the next reminder inside
        // the 24-hour free day. Lead from the next high so it fires an hour from now; the planner must drop the
        // reminders that fall after the free day ends.
        val high = AlertRule(stationId = StationId("noaa/9449880"), trigger = AlertTrigger.TideExtreme(high = true))
        val now = Instant.now()
        val next = runBlocking {
            OccurrenceResolver.resolve(high, AndroidPredictionRepository(app), TimeSpan(now.plusSeconds(7200), now.plus(NEXT_HIGH_SEARCH)),
                ZoneOffset.UTC, 48.0, -123.0, 0.5).first().event
        }
        val rule = high.copy(leadSeconds = next.epochSecond - now.plusSeconds(3600).epochSecond)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking {
                access.update(ReminderEntitlement.Free)
                rules.upsert(rule)
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("slackwater://premium/?source=test")).setPackage(app.packageName)
            ActivityScenario.launch<PremiumLinkActivity>(intent).use {
                compose.onNodeWithText(app.getString(R.string.premium_try_free_day)).performClick()
                val scheduled = scope.async {
                    withTimeout(30_000) {
                        DeliveryPlanStore.get(app).plan.first { plan ->
                            plan?.notifications?.any { it.ruleId == rule.id } == true
                        }
                    }
                }
                compose.waitUntil(35_000) { scheduled.isCompleted }
                val plan = runBlocking { scheduled.await()!! }
                val trial = runBlocking { access.current() } as ReminderEntitlement.FreeDay
                assertTrue(plan.notifications.filter { it.ruleId == rule.id }.all { it.fire < trial.ends })
            }
        } finally {
            scope.cancel()
            runBlocking {
                rules.remove(rule.id)
                val started = original.started
                access.update(if (started == null) ReminderEntitlement.Free
                    else ReminderEntitlement.FreeDay(started.plusSeconds(86_400), started))
                if (original.purchased) access.syncPurchase(true)
            }
        }
    }
}
