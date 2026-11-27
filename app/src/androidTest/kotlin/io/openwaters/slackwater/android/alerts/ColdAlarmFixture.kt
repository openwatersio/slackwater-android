package io.openwaters.slackwater.android.alerts

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.planning.AlertRule
import io.openwaters.slackwater.android.core.planning.AlertTrigger
import io.openwaters.slackwater.android.core.planning.OccurrenceResolver
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.AlertReconciliation
import io.openwaters.slackwater.android.data.AndroidPredictionRepository
import io.openwaters.slackwater.android.data.DeliveryPlanStore
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Host-driven test on a disposable emulator. The host kills the process after scheduling. */
class ColdAlarmFixture {
    @Test fun seed() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedColdAlarm") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val rule = AlertRule(stationId = StationId("noaa/9449880"), trigger = AlertTrigger.TideExtreme(high = true))
        val now = Instant.now()
        val event = OccurrenceResolver.resolve(rule, AndroidPredictionRepository(app),
            TimeSpan(now.plusSeconds(900), now.plus(NEXT_HIGH_SEARCH)), ZoneOffset.UTC,
            48.0, -123.0, 0.5).first().event
        val fire = Instant.now().plusSeconds(45).epochSecond
        val saved = rule.copy(once = event, leadSeconds = event.epochSecond - fire)
        ReminderAccessStore.get(app).update(ReminderEntitlement.Purchased)
        assertTrue(AlertReconciliation.get(app).save(saved))
        val plans = DeliveryPlanStore.get(app)
        withTimeout(30000) {
            while (plans.plan.first()?.notifications.orEmpty().none { it.ruleId == rule.id }) delay(50)
        }
        AlarmScheduler.get(app).syncLatest()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("station", saved.stationId.value)
            putString("event", event.toString())
            putString("fire", Instant.ofEpochSecond(fire).toString())
            putString("key", "${saved.id}.${event.epochSecond}")
        })
    }
}
