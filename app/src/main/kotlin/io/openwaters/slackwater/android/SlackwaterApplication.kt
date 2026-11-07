package io.openwaters.slackwater.android

import android.app.Application
import androidx.work.Configuration

class SlackwaterApplication : Application(), Configuration.Provider {
    // CalendarJobs owns 2401 and 2402 in the same JobScheduler namespace.
    override val workManagerConfiguration: Configuration = Configuration.Builder()
        .setJobSchedulerJobIdRange(10_000, 20_000)
        .build()
}
