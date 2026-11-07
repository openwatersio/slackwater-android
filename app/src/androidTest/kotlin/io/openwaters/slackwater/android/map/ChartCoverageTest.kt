package io.openwaters.slackwater.android.map

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.data.UserStateStore
import io.openwaters.slackwater.android.stations.StationListModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartCoverageTest {
    @Test fun aStationDownloadRequestKeepsItsChartUntilItIsReleased() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requests = UserStateStore.get(context).downloads
        val initial = withTimeout(60_000) { chartCoverage(context).first { !it.incompleteStations } }
        assertTrue(initial.specs.any { it.key == "world" })
        val cancelSet = requests.state.first().getOrThrow().cancelled
        val station = StationListModel.get(context).catalog.value!!.stations.first { station ->
            station.id !in cancelSet && initial.specs.none { it.key == "station/${station.id.value}" }
                && kotlin.math.abs(station.latitude) < 80
        }
        val key = "station/${station.id.value}"
        val ids = listOf(station.id)
        try {
            requests.requestDownloads(ids)
            val requested = withTimeout(20_000) { chartCoverage(context).first { !it.incompleteStations } }
            assertTrue(requested.specs.any { it.key == key })
        } finally {
            requests.releaseDownloadRequests(ids)
        }
        val released = withTimeout(20_000) { chartCoverage(context).first { !it.incompleteStations } }
        assertFalse(released.specs.any { it.key == key })
    }
}
