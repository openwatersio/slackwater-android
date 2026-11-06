package io.openwaters.slackwater.android.alerts

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.PredictionRepository
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal fun BroadcastReceiver.background(work: suspend () -> Unit) {
    val result = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            work()
        } catch (error: Exception) {
            Log.w("Slackwater", "Alert broadcast failed", error)
        } finally {
            result.finish()
        }
    }
}

internal class AlertDeliveryVerifier(
    private val predictions: PredictionRepository,
    private val catalog: StationCatalog,
) {
    suspend fun stationFor(
        expected: AlertOccurrence, rule: AlertRule?, thresholdKnots: Double, now: Instant, entitlement: ReminderEntitlement,
    ): StationIdentity? {
        if (expected.fire > now || !expected.isDeliverableAt(now) || !entitlement.allows(now)) return null
        if (rule == null || rule.id != expected.ruleId || !rule.enabled || rule.heightNeedsReview || rule.isExpired(now)) return null
        val station = (catalog.resolve(rule.stationId) as? StationResolution.Live)?.identity ?: return null
        val zone = station.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
        // Sample either side of the target so local extrema and crossings can be observed.
        val span = TimeSpan(expected.event.minusSeconds(3600), expected.event.plusSeconds(3600))
        val current = OccurrenceResolver.resolve(rule, predictions, span, zone, station.latitude, station.longitude, thresholdKnots)
        return station.takeIf {
            current.any { it.key == expected.key && it.fire == (expected.originalFire ?: expected.fire) && it.noWindow == expected.noWindow }
        }
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = AlarmScheduler.occurrenceKey(intent) ?: return
        background { deliver(context.applicationContext, key) }
    }

    internal suspend fun deliver(app: Context, key: String, clock: Clock = Clock.systemUTC()) {
        val scheduler = AlarmScheduler.get(app)
        try {
            alertInputLock.withLock {
                val plans = DeliveryPlanStore.get(app)
                val expected = plans.plan.first()?.notifications?.find { it.key == key } ?: return@withLock
                val rule = AlertRuleStore.get(app).rules.first().find { it.id == expected.ruleId }
                val entitlement = ReminderAccessStore.get(app).current()
                val threshold = rule?.let {
                    ReadingPreferencesStore.get(app).preferences.first().getOrThrow().effectiveThresholdKn(it.stationId)
                } ?: 0.0
                val verifier = AlertDeliveryVerifier(AndroidPredictionRepository(app), BundledStationCatalog(app.assets))
                val station = verifier.stationFor(expected, rule, threshold, clock.instant(), entitlement)
                val valid = station != null && rule != null && scheduler.access().notificationsAllowed
                if (plans.consume(expected, clock.instant(), entitlement, valid) && rule != null && station != null) {
                    val postingAt = clock.instant()
                    if (entitlement.allows(postingAt) && expected.isDeliverableAt(postingAt)) {
                        post(app, station, rule, expected)
                    }
                }
            }
        } finally {
            scheduler.syncLatest()
        }
    }

    private fun post(app: Context, station: StationIdentity, rule: AlertRule, entry: AlertOccurrence) {
        val label = when (val trigger = rule.trigger) {
            AlertTrigger.SlackWindowOpens -> if (entry.noWindow) "Slack" else "Slack window opens"
            AlertTrigger.Slack -> "Slack"
            AlertTrigger.Eclipse -> "Eclipse begins"
            is AlertTrigger.CurrentPeak -> if (trigger.flood) "Max flood" else "Max ebb"
            is AlertTrigger.TideExtreme -> if (trigger.high) "High tide" else "Low tide"
            is AlertTrigger.TideCrossing -> if (trigger.rising) "Rising past ${trigger.heightM} m" else "Falling past ${trigger.heightM} m"
        }
        val zone = station.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone).format(entry.event)
        val open = PendingIntent.getActivity(app, 0, AlertDestination.intent(app, station.id, entry.event),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(app, AlarmScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(station.name)
            .setContentText("$label · $time")
            .setWhen(entry.event.toEpochMilli())
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(entry.key, 1, notification)
    }
}
