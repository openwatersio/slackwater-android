package io.openwaters.slackwater.android.widgets

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WidgetRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = updates.withLock {
        if (REFRESH in tags) synchronized(Companion) {
            // Changes after this point share one follow-up that reads the newer inputs.
            nextRefreshName = refreshName()
        }
        try {
            if (hasStationWidgets(applicationContext)) updateStationWidgets(applicationContext)
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        private val updates = Mutex()
        private const val PERIODIC = "station-widget-periodic"
        private const val REFRESH = "station-widget-refresh"

        private fun refreshName() = "$REFRESH-${UUID.randomUUID()}"
        private var nextRefreshName = refreshName()

        @Synchronized
        fun refresh(context: Context) {
            if (!hasStationWidgets(context)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                nextRefreshName, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().addTag(REFRESH).build(),
            )
        }

        fun schedule(context: Context) {
            if (!hasStationWidgets(context)) return
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        fun cancel(context: Context) {
            if (hasStationWidgets(context)) return
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelAllWorkByTag(REFRESH)
        }
    }
}
