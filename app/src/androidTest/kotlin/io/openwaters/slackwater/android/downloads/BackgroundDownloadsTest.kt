package io.openwaters.slackwater.android.downloads

import android.app.Notification
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.data.BackgroundDownloadCount
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The notification and its Cancel. Scheduling the user-initiated job and the run itself need a visible app and IWLS. */
class BackgroundDownloadsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scheduler = context.getSystemService(JobScheduler::class.java)

    @After fun removeJob() = scheduler.cancel(BackgroundDownloads.JOB_ID)

    private fun notification(count: BackgroundDownloadCount?) = BackgroundDownloads.notification(context, count)
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test fun theNotificationShowsTheCountOrTheWait() {
        val counted = notification(BackgroundDownloadCount(ready = 3, total = 8, active = true, waitingToRetry = false))
        assertEquals(context.getString(R.string.gate_downloading), counted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(context.getString(R.string.download_stations_ready, 3, 8), counted.text())
        assertEquals(8, counted.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(3, counted.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(counted.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(counted.flags and Notification.FLAG_ONGOING_EVENT != 0)

        val waiting = notification(BackgroundDownloadCount(ready = 3, total = 8, active = true, waitingToRetry = true))
        assertEquals(context.getString(R.string.download_waiting_retry), waiting.text())

        // Before the first count, and for work outside the automatic set, there is no fraction to show.
        for (uncounted in listOf(null, BackgroundDownloadCount(ready = 0, total = 0, active = true, waitingToRetry = false))) {
            val starting = notification(uncounted)
            assertNull(starting.text())
            assertTrue(starting.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        }
    }

    @Test fun theNotificationsCancelRemovesTheJob() {
        // A stand-in for the user-initiated job, which only a visible app may schedule. It never runs: the device is not idle.
        val job = JobInfo.Builder(BackgroundDownloads.JOB_ID, ComponentName(context, BackgroundDownloadService::class.java))
            .setRequiresDeviceIdle(true)
            .build()
        assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(job))
        assertNotNull(scheduler.getPendingJob(BackgroundDownloads.JOB_ID))

        val action = notification(null).actions.single()
        assertEquals(context.getString(R.string.alert_cancel), action.title.toString())
        action.actionIntent.send()

        val deadline = System.currentTimeMillis() + 5_000
        while (scheduler.getPendingJob(BackgroundDownloads.JOB_ID) != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertNull(scheduler.getPendingJob(BackgroundDownloads.JOB_ID))
    }
}
