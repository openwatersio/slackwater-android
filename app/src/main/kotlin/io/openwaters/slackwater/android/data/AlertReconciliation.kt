package io.openwaters.slackwater.android.data

import android.content.Context
import android.util.Log
import io.openwaters.slackwater.android.alerts.AlarmScheduler
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.alerts.activeAt
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationResolution
import java.io.IOException
import java.time.DateTimeException
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** A transient store failure must not detach the process from future changes. */
internal fun <T> Flow<T>.retryAlertReads(backoffMillis: Long = 1000): Flow<T> =
    retryWhen { cause, attempt ->
        if (cause !is IOException) return@retryWhen false
        if (attempt == 0L) Log.w("Slackwater", "Alert inputs unavailable; will retry", cause)
        delay(backoffMillis * (1L shl attempt.coerceAtMost(6).toInt()))
        true
    }

/** Each pass reads the latest inputs. Changes during a pass wake the same worker once more. */
internal class AlertPassQueue(scope: CoroutineScope, run: suspend (Set<UUID>) -> Unit) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val saved = mutableSetOf<UUID>()

    init {
        scope.launch {
            for (signal in wake) {
                val ids = synchronized(saved) { saved.toSet().also { saved.clear() } }
                try {
                    run(ids)
                } catch (e: IOException) {
                    synchronized(saved) { saved.addAll(ids) }
                    Log.w("Slackwater", "Alert storage unavailable; retrying on the next change", e)
                }
            }
        }
    }

    fun request(newRule: UUID? = null) {
        synchronized(saved) {
            newRule?.let(saved::add)
            wake.trySend(Unit)
        }
    }
}

class AlertReconciliation private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = Clock.systemUTC()
    private val rules = AlertRuleStore.get(context)
    private val preferences = ReadingPreferencesStore.get(context)
    private val models = ModelStore.get(context)
    private val catalog = BundledStationCatalog(context.assets)
    private val predictions = AndroidPredictionRepository(context, models)
    private val plans = DeliveryPlanStore.get(context)
    private val entitlement = ReminderAccessStore.get(context)
    private val alarms = AlarmScheduler.get(context)
    private val passes = AlertPassQueue(scope, ::reconcile)

    init {
        scope.launch {
            // Alarm delivery checks the saved threshold immediately, so request a new plan on each change.
            val settings = preferences.preferences.map {
                val prefs = it.getOrThrow()
                prefs.slackWindowKnots to prefs.stationDeltasKn
            }.distinctUntilChanged().retryAlertReads()
            combine(rules.rules.retryAlertReads(), settings, models.revision, entitlement.changes.retryAlertReads()) { _, _, _, _ -> Unit }
                .collect { passes.request() }
        }
    }

    fun foreground() = passes.request()

    suspend fun entitlementChanged(access: ReminderEntitlement) = entitlement.update(access)

    suspend fun save(rule: AlertRule, confirmHeight: Boolean = false): Boolean {
        if (!entitlement.current().activeAt(clock.instant())) return false
        val crossing = rule.trigger as? AlertTrigger.TideCrossing
        if (confirmHeight && crossing != null) rules.confirmHeight(rule, crossing.heightM) else rules.upsert(rule)
        passes.request(rule.id)
        return true
    }

    private suspend fun reconcile(newRules: Set<UUID>) {
        val now = clock.instant()
        rules.expire(now)
        val active = rules.rules.first().filter { it.enabled && !it.heightNeedsReview }
        val access = entitlement.current()
        val occurrences = mutableListOf<AlertOccurrence>()
        if (active.isNotEmpty() && access != ReminderEntitlement.Free) {
            val settings = preferences.preferences.first().getOrThrow()
            for (rule in active) {
                val station = (catalog.resolve(rule.stationId) as? StationResolution.Live)?.identity ?: continue
                val zone = station.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
                val searchSeconds = NOTIFICATION_HORIZON.seconds + rule.leadSeconds.coerceAtMost(CALENDAR_HORIZON.seconds)
                val start = if (rule.leadSeconds == 0L) now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) else now
                val span = TimeSpan(start, now.plusSeconds(minOf(searchSeconds, CALENDAR_HORIZON.seconds)))
                try {
                    occurrences += OccurrenceResolver.resolve(rule, predictions, span, zone,
                        station.latitude, station.longitude, settings.effectiveThresholdKn(station.id))
                } catch (e: DateTimeException) {
                    Log.w("Slackwater", "Rule ${rule.id} has an unsupported time", e)
                } catch (e: IllegalArgumentException) {
                    Log.w("Slackwater", "Rule ${rule.id} is outside prediction limits", e)
                }
            }
        }
        plans.rebuild(active, occurrences, emptyList(), now, access, newRules)
        alarms.syncLatest()
    }

    companion object {
        @Volatile private var instance: AlertReconciliation? = null
        fun get(context: Context): AlertReconciliation = instance ?: synchronized(this) {
            instance ?: AlertReconciliation(context.applicationContext).also { instance = it }
        }
    }
}
