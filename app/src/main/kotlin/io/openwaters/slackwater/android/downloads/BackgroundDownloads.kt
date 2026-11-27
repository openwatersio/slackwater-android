package io.openwaters.slackwater.android.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.data.BackgroundDownloadCount
import io.openwaters.slackwater.android.data.BackgroundRunMonitor
import io.openwaters.slackwater.android.data.ModelStore
import io.openwaters.slackwater.android.data.OnlineGateDownloads
import io.openwaters.slackwater.android.data.TidePreviewDownloads
import io.openwaters.slackwater.android.data.automaticFitCandidates
import io.openwaters.slackwater.android.data.automaticOnlineCandidates
import io.openwaters.slackwater.android.data.backgroundDownloadsAllowed
import io.openwaters.slackwater.android.data.onlineDownloadsActive
import io.openwaters.slackwater.android.stations.StationListModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * The selected Canadian downloads, continued after the app leaves the screen in a user-initiated
 * data transfer job (Android 14 and later), the counterpart of iOS continued processing. What
 * Android grants, and the limits around it, follow iOS `BackgroundDownloads`:
 *  - The job must be scheduled while the app is visible, so the activity schedules it while
 *    started: when a selected station starts transferring, and when a person returns with
 *    unfinished work.
 *  - The system can stop the job at any time (thermal state, constraints, memory), and the
 *    person can stop it from the notification or the Task Manager. A stopped run is never
 *    restarted: the queue's requests and the IWLS week caches persist, and the next launch resumes.
 *  - The run continues the queue in the process the app was visible in. A job the system starts
 *    in a fresh process ends at once: that process has no location fix and none of the app's
 *    session, and iOS likewise never relaunches a swiped-away app for this.
 *  - Earlier Android versions pause these downloads when the app leaves the screen, as before.
 */
internal object BackgroundDownloads {
    // CalendarJobs owns 2401 and 2402; WorkManager owns 10000 to 20000.
    const val JOB_ID = 2403
    const val NOTIFICATION_ID = 2403
    private const val CHANNEL = "downloads"

    /** Set by the notification's Cancel; the next start of the app clears it. */
    @Volatile private var stoppedByUser = false
    /** A rotation or other configuration change restarts the activity without leaving the app. */
    @Volatile private var changingConfiguration = false
    /** Whether the app has been visible in this process, the only kind of process a run continues in. */
    @Volatile var visibleThisProcess = false
        private set
    /** The run this process is executing; a pending job with no run here is a leftover to replace. */
    @Volatile private var running: Any? = null
    /** Counts runs that ended, so a visible app schedules a new one while the selection still allows it. */
    private val runsEnded = MutableStateFlow(0)

    fun runStarted(run: Any) {
        running = run
    }

    /** Each run clears only its own entry, so a stopped run finishing late cannot clear its successor's. */
    fun runFinished(run: Any) {
        if (running === run) running = null
        runsEnded.update { it + 1 }
    }

    /** Called as the activity stops, so Cancel outlasts a rotation but not leaving the app. */
    fun activityStopping(changingConfigurations: Boolean) {
        changingConfiguration = changingConfigurations
    }

    /** While the activity is started, schedule the job whenever the selection allows background work. */
    suspend fun whileVisible(context: Context) {
        visibleThisProcess = true
        if (!changingConfiguration) stoppedByUser = false
        changingConfiguration = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val app = context.applicationContext
        val store = StationListModel.get(app).places.downloads
        val queue = TidePreviewDownloads.get(app)
        val online = OnlineGateDownloads.get(app)
        // A run that ends while the app is on screen (a handoff, a system stop) is followed by a new one
        // if the selection still allows it, so leaving the app afterwards still continues.
        combine(store.selection, queue.states, online.weekStates, queue.networkPause, runsEnded) { selection, states, weeks, pause, ended ->
            backgroundDownloadsAllowed(selection, states, queue::isCurrentWork, onlineDownloadsActive(weeks.values), pause) to ended
        }.distinctUntilChanged().collect { (allowed, _) -> if (allowed && !stoppedByUser) schedule(app) }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // A run in progress here is kept. Any other pending job is replaced: one still waiting for a
        // network loses nothing, and one the system kept after the process died would run as an
        // ordinary job with an ordinary time limit.
        if (running != null && scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackgroundDownloadService::class.java))
            .setUserInitiated(true)
            // NETWORK_TYPE_ANY, unlike a default NetworkRequest, also accepts a VPN as the default network.
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        try {
            // Scheduling fails when the app is not visible; the next start schedules it again.
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w("Slackwater", "Background downloads were not scheduled")
        } catch (refused: RuntimeException) {
            // The foreground queue carries on either way.
            Log.w("Slackwater", "Background downloads were refused", refused)
        }
    }

    fun cancelByUser(context: Context) {
        stoppedByUser = true
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    fun notification(context: Context, count: BackgroundDownloadCount?): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.chart_downloads), NotificationManager.IMPORTANCE_LOW))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val cancel = PendingIntent.getBroadcast(context, 0, Intent(context, BackgroundDownloadCancelReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            count == null || count.total == 0 -> null
            count.waitingToRetry -> context.getString(R.string.download_waiting_retry)
            else -> context.getString(R.string.download_stations_ready, count.ready, count.total)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.gate_downloading))
            .apply { if (text != null) setContentText(text) }
            .setProgress(count?.total ?: 0, count?.ready ?: 0, count == null || count.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.alert_cancel), cancel).build())
            .build()
    }

    /** Keep the queue running for the selected stations until nothing is left to do, or the run stalls. */
    suspend fun run(context: Context, update: (BackgroundDownloadCount) -> Unit) {
        val queue = TidePreviewDownloads.get(context)
        val online = OnlineGateDownloads.get(context)
        val models = ModelStore.get(context)
        val store = StationListModel.get(context).places.downloads
        queue.backgroundStarted()
        try {
            coroutineScope {
                val selected = automaticFitCandidates(context, holdRadius = true).shareIn(this, SharingStarted.Eagerly, 1)
                launch { queue.fitWhileStarted(selected) }
                // Data Saver keeps background transfers off metered networks, missing weeks included.
                val onlineWanted = combine(automaticOnlineCandidates(context, selected), online.restricted) { ids, restricted ->
                    if (restricted) emptySet() else ids
                }
                launch { online.prefetchWhileStarted(onlineWanted) }
                // The first pass decides whether the online gates have anything to do. It is not timed
                // out: cancelling a slow week would make it start over; the stall check bounds it.
                val firstPass = async {
                    try {
                        online.prefetch(onlineWanted.first())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w("Slackwater", "Background gate downloads failed", error)
                    }
                }
                withTimeoutOrNull(STARTUP_MILLIS) { queue.progress.first { it != null } }
                val monitor = BackgroundRunMonitor()
                var shown: BackgroundDownloadCount? = null
                while (true) {
                    val count = queue.progress.value?.let {
                        BackgroundDownloadCount.of(it, queue.states.value, online.weekStates.value.values, firstPass.isActive)
                    }
                    if (count != null && count != shown) {
                        update(count)
                        shown = count
                    }
                    if (!monitor.keepRunning(count, models.revision.value, queue.networkPause.value,
                            store.selection.first().backgroundRequested, SystemClock.elapsedRealtime())) break
                    delay(POLL_MILLIS)
                }
                coroutineContext.cancelChildren()
            }
        } finally {
            queue.backgroundStopped()
        }
    }

    private const val STARTUP_MILLIS = 30_000L
    private const val POLL_MILLIS = 2_000L
}

/** Runs only on Android 14 and later, the only versions that schedule it. */
class BackgroundDownloadService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        // A fresh process the system started for a leftover job: end it before showing anything.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !BackgroundDownloads.visibleThisProcess) return false
        setNotification(params, BackgroundDownloads.NOTIFICATION_ID, BackgroundDownloads.notification(this, null),
            JOB_END_NOTIFICATION_POLICY_REMOVE)
        BackgroundDownloads.runStarted(params)
        job = scope.launch {
            try {
                BackgroundDownloads.run(applicationContext) { count ->
                    setNotification(params, BackgroundDownloads.NOTIFICATION_ID, BackgroundDownloads.notification(this@BackgroundDownloadService, count),
                        JOB_END_NOTIFICATION_POLICY_REMOVE)
                }
                // Finished before the run is marked over, so a visible app cannot replace a job still ending.
                jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("Slackwater", "Background downloads stopped", error)
                jobFinished(params, false)
            } finally {
                BackgroundDownloads.runFinished(params)
            }
        }
        return true
    }

    // The system does not restart a stopped run; a visible app schedules a new one once the run's
    // coroutine has ended and cleared it.
    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return false
    }

    // The queue watches the network itself; without this override Android logs a warning every few seconds.
    override fun onNetworkChanged(params: JobParameters) = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** The notification's Cancel: stop this run until the app is next opened. The requests stay. */
class BackgroundDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BackgroundDownloads.cancelByUser(context)
    }
}
