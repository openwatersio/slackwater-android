package io.openwaters.slackwater.android.data

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.StationId
import org.junit.Assert.*
import org.junit.Test

class BundledTideDatabaseTest {
    @Test fun readsFridayHarborFromThePackagedDatabase() {
        val reader = BundledTideDatabase(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        assertNull(reader.station(StationId("unknown/missing")))
        val harbor = requireNotNull(reader.station(StationId("noaa/9449880")))
        assertEquals("Friday Harbor", harbor.name)
        assertEquals(1.387, requireNotNull(harbor.chartDatumShift), 0.00001)
        val constituents = harbor.constituents
        assertEquals(37, constituents.size)
        val m2 = constituents.single { it.name == "M2" }
        assertEquals(0.557, m2.amplitude, 0.00001)
        assertEquals(10.6, m2.phase, 0.00001)
        val norwegian = requireNotNull(reader.station(StationId("kartverket/VAW")))
        assertTrue(norwegian.attribution.contains("Kartverket"))
        assertTrue(norwegian.attribution.contains("CC BY 4.0"))
    }
}
