package io.openwaters.slackwater.android

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.openwaters.slackwater.android.data.ListPreferencesStore
import kotlinx.coroutines.runBlocking

/**
 * Records that the station list has already asked for location and shown its tour, so no test's activity sits behind
 * the system permission dialog or opens on the tour's station. Tests of the ask and the tour drive them directly.
 */
class SlackwaterTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        MainActivity.automaticChartDownloads = false
        super.onCreate(arguments)
        runBlocking {
            val list = ListPreferencesStore.get(targetContext)
            list.markLocationAsked()
            // Nor does any test land on the first-run tour's station; the tour's own tests clear this.
            list.markTourSeen()
            // A filter a previous run left on would hide stations the tests open by name.
            list.setSeriesFilter(null)
        }
    }
}
