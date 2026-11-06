package io.openwaters.slackwater.android.calendars

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.*

/** Persisted jobs roll the 90-day horizon forward and request cleanup when a free day ends. */
internal object CalendarJobs {
    private const val REFRESH = 2401
    private const val EXPIRY = 2402
    private const val END = "freeDayEndMillis"

    fun update(context: Context, hasSubscriptions: Boolean, trialEnd: Instant?) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, CalendarRefreshService::class.java)
        if (!hasSubscriptions) {
            scheduler.cancel(REFRESH)
            scheduler.cancel(EXPIRY)
            return
        }
        if (scheduler.getPendingJob(REFRESH) == null) {
            scheduler.schedule(JobInfo.Builder(REFRESH, component)
                .setPeriodic(Duration.ofDays(1).toMillis()).setPersisted(true).build())
        }
        val end = trialEnd?.toEpochMilli()
        val current = scheduler.getPendingJob(EXPIRY)
        // An expiry job may itself be reconciling now. Let it finish when the trial ends or converts.
        if (end != null && end > System.currentTimeMillis() && current?.extras?.getLong(END) != end) {
            val delay = (end - System.currentTimeMillis()).coerceAtLeast(0)
            scheduler.schedule(JobInfo.Builder(EXPIRY, component)
                .setMinimumLatency(delay).setPersisted(true)
                .setExtras(PersistableBundle().apply { putLong(END, end) }).build())
        }
    }
}

class CalendarRefreshService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        running[params.jobId] = scope.launch {
            val retry = try {
                !withContext(Dispatchers.IO) { CalendarRepository.get(applicationContext).reconcile() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("Slackwater", "Station calendar refresh deferred", error)
                true
            }
            running.remove(params.jobId)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
