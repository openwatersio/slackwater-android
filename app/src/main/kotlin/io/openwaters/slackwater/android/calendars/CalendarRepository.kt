package io.openwaters.slackwater.android.calendars

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CalendarRepository private constructor(private val app: Context) {
    private val state = CalendarStateStore.get(app)
    private val access = ReminderAccessStore.get(app)
    private val catalog = BundledStationCatalog(app.assets)
    private val models = ModelStore.get(app)
    private val predictions = AndroidPredictionRepository(app, models)
    private val preferences = ReadingPreferencesStore.get(app)
    private val provider = StationCalendarProvider(app)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)

    val subscriptions = state.state.map { it.subscriptions }.distinctUntilChanged()
    val cleanupPending = state.state.map { snapshot ->
        snapshot.invalidations.isNotEmpty() || snapshot.subscriptions.any { it.pendingWork || it.pendingDeletion }
    }.distinctUntilChanged()

    init {
        scope.launch {
            val thresholds = preferences.preferences.settledThresholds().retryAlertReads()
            val heights = preferences.preferences.map { it.getOrThrow().savedHeightUnits }.distinctUntilChanged().retryAlertReads()
            combine(access.changes.retryAlertReads(), thresholds, heights, models.revision,
                state.state.map { it.invalidations }.distinctUntilChanged().retryAlertReads()) { _, _, _, _, _ -> Unit }
                .collect { foreground() }
        }
        scope.launch {
            for (request in requests) {
                try { reconcile() } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Log.w("Slackwater", "Calendar pass will retry", error) }
            }
        }
    }

    fun foreground() { requests.trySend(Unit) }

    suspend fun canSubscribe(stationId: StationId): Boolean = withContext(Dispatchers.IO) {
        val station = (catalog.resolve(stationId) as? StationResolution.Live)?.identity ?: return@withContext false
        if (stationCalendarTitle(station) == null) return@withContext false
        val now = Instant.now()
        stationCalendarPrediction(station, now, predictions).coversCalendar(now)
    }

    suspend fun subscribe(stationId: StationId): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!access.current().allows(Instant.now())) return@withLock false
            // Complete the first 90-day plan before recording any subscription or creating an external calendar.
            val prepared = publication(stationId, Instant.now())
            if (prepared.events == null || prepared.source == null) return@withLock false
            val allowed = alertInputLock.withLock checkAccess@{
                access.finishPendingCalendarPurchase()
                val entitlement = access.current()
                if (!entitlement.allows(Instant.now()) || prepared.modelRevision != models.revision.value) return@checkAccess false
                state.edit { snapshot ->
                    if (snapshot.subscriptions.any { it.stationId == stationId }) snapshot
                    else {
                        val origin = if (entitlement == ReminderEntitlement.Purchased) CalendarOrigin.PURCHASED else CalendarOrigin.FREE_DAY
                        snapshot.copy(subscriptions = snapshot.subscriptions + StationSubscription(
                            stationId, origin = origin, trialEnd = (entitlement as? ReminderEntitlement.FreeDay)?.ends,
                            publishedSource = prepared.source,
                        ))
                    }
                }
                true
            }
            if (!allowed) return@withLock false
            updateJobs()
            process(stationId, prepared)
        }
    }

    suspend fun eventCount(stationId: StationId): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val sub = subscriptions.first().find { it.stationId == stationId } ?: return@withLock 0
            provider.count(stationId, sub.calendarId, Instant.now())
        }
    }

    suspend fun unsubscribe(stationId: StationId) = withContext(Dispatchers.IO) {
        lock.withLock {
            state.edit { snapshot -> snapshot.copy(subscriptions = snapshot.subscriptions.map {
                if (it.stationId == stationId) it.copy(pendingDeletion = true, pendingWork = true) else it
            }) }
            updateJobs()
            process(stationId)
            updateJobs()
        }
    }

    private suspend fun updateJobs() {
        val rows = subscriptions.first()
        val now = Instant.now()
        val expiry = rows.filter { !it.pendingDeletion }.mapNotNull { it.trialEnd }.filter { it > now }.minOrNull()
        CalendarJobs.update(app, rows.isNotEmpty(), expiry)
    }

    suspend fun reconcile(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            alertInputLock.withLock {
                access.finishPendingCalendarPurchase()
                val entitlement = access.current()
                if (entitlement == ReminderEntitlement.Purchased) state.convertPurchased()
                if (entitlement is ReminderEntitlement.FreeDay) {
                    state.edit { snapshot -> snapshot.copy(subscriptions = snapshot.subscriptions.map { sub ->
                        if (sub.origin == CalendarOrigin.FREE_DAY && sub.trialEnd != entitlement.ends) sub.copy(trialEnd = entitlement.ends) else sub
                    }) }
                }
            }
            updateJobs()
            var success = true
            for (sub in subscriptions.first()) if (!process(sub.stationId)) success = false
            updateJobs()
            success
        }
    }

    private data class Publication(
        val modelRevision: Long, val source: String?, val title: String?, val events: List<StationCalendarEvent>?,
        val rejected: Boolean,
    )

    private suspend fun publication(id: StationId, now: Instant): Publication {
        val revision = models.revision.value
        val station = (catalog.resolve(id) as? StationResolution.Live)?.identity
            ?: return Publication(revision, "unavailable:${id.value}", null, null, true)
        val source = predictions.calendarSource(station)
        val result = stationCalendarPrediction(station, now, predictions)
        val settings = preferences.preferences.first().getOrThrow()
        val displayContext = app.createConfigurationContext(Configuration(app.resources.configuration))
        val events = stationCalendarEvents(station, now, settings.effectiveThresholdKn(id),
            settings.heightUnits() == HeightUnits.IMPERIAL, result)
            ?.let(displayContext::localizeCalendarEvents)
        val rejected = result is PredictionResult.Unavailable && result.reason != PredictionResult.Reason.OUTSIDE_COVERAGE
        if (models.revision.value != revision) throw java.io.IOException("Calendar input changed during calculation")
        return Publication(revision, source, localizedStationCalendarTitle(displayContext, station), events, rejected)
    }

    private suspend fun process(id: StationId, firstPlan: Publication? = null): Boolean {
        // A model writer journals before rename. Wait for that rename before reading its journal entry.
        val snapshot = alertInputLock.withLock { state.state.first() }
        val sub = snapshot.subscriptions.find { it.stationId == id } ?: return true
        var invalidation = snapshot.invalidations[id]
        return try {
            val now = Instant.now()
            var calendarId = sub.calendarId
            var source = sub.publishedSource
            if (sub.pendingDeletion || !sub.publishes(access.current(), now)) {
                provider.clearFuture(id, calendarId, now, removeCalendar = sub.pendingDeletion)
            } else {
                val plan = firstPlan?.takeIf { it.modelRevision == models.revision.value } ?: publication(id, now)
                val writingAt = Instant.now()
                if (!sub.publishes(access.current(), writingAt)) {
                    provider.clearFuture(id, calendarId, writingAt)
                } else if (plan.events != null && plan.title != null && plan.source != null) {
                    calendarId = provider.reconcile(id, calendarId, plan.title, plan.events, writingAt)
                    source = plan.source
                } else if (plan.rejected && plan.source != null &&
                    (invalidation != null || (source != plan.source && (calendarId != null || source != null)))) {
                    // A downgraded build can remove a model without our journal. Record the detected
                    // change before calling the provider so denied cleanup survives the next restart.
                    if (invalidation == null) {
                        alertInputLock.withLock {
                            if (models.revision.value != plan.modelRevision) throw java.io.IOException("Calendar source changed before cleanup")
                            state.invalidate(setOf(id))
                            invalidation = state.state.first().invalidations[id]
                        }
                    }
                    provider.clearFuture(id, calendarId, writingAt)
                    source = plan.source
                } else {
                    // Short coverage or an unreadable model cannot establish that published rows are obsolete.
                    return sub.calendarId != null && !sub.pendingWork && invalidation == null
                }
            }
            state.edit { current ->
                val invalidations = if (current.invalidations[id] == invalidation) current.invalidations - id else current.invalidations
                val rows = if (sub.pendingDeletion) current.subscriptions.filterNot { it.stationId == id }
                    else current.subscriptions.map { if (it.stationId == id) it.copy(calendarId = calendarId, pendingWork = false, publishedSource = source) else it }
                current.copy(subscriptions = rows, invalidations = invalidations)
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w("Slackwater", "Calendar work for ${id.value} will retry", error)
            state.edit { current -> current.copy(subscriptions = current.subscriptions.map {
                if (it.stationId == id) it.copy(pendingWork = true) else it
            }) }
            false
        }
    }

    companion object {
        private var shared: CalendarRepository? = null
        @Synchronized fun get(context: Context): CalendarRepository =
            shared ?: CalendarRepository(context.applicationContext).also { shared = it }
    }
}

internal fun localizedStationCalendarTitle(context: Context, station: StationIdentity): String? {
    if (context.resources.configuration.locales[0].language == "en") return stationCalendarTitle(station)
    return when (station.series) {
        StationSeries.Tide -> context.getString(R.string.panel_port, station.name)
        StationSeries.Current -> context.getString(R.string.detail_related_currents, station.name)
        null -> null
    }
}
