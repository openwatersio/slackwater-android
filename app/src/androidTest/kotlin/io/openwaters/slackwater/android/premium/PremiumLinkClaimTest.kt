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
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.AlertRule
import io.openwaters.slackwater.android.core.planning.AlertTrigger
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.AlertRuleStore
import io.openwaters.slackwater.android.data.DeliveryPlanStore
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
        val rule = AlertRule(stationId = StationId("noaa/9449880"), trigger = AlertTrigger.TideExtreme(high = true))
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
