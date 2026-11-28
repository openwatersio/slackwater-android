package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.NodeCorrections
import io.openwaters.slackwater.database.Station.NodeCorrections as DatabaseCorrections
import io.openwaters.slackwater.database.StationDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class BundledPredictionTest {
    @Test fun mapsDatabaseCorrectionTypesToTheEngine() {
        assertEquals(NodeCorrections.Iho, engineNodeCorrections(DatabaseCorrections.IHO, null))
        assertEquals(NodeCorrections.Schureman, engineNodeCorrections(DatabaseCorrections.SCHUREMAN, null))
        assertEquals(NodeCorrections.Foreman(-42.8), engineNodeCorrections(DatabaseCorrections.FOREMAN, -42.8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForemanWithoutItsReferenceLatitude() {
        engineNodeCorrections(DatabaseCorrections.FOREMAN, null)
    }

    @Test fun predictsPackagedTideWithThePinnedReaderAndEngine() {
        val database = StationDatabase.open(File("src/main/assets/slackwater/stations.tcdb"))
        val harbor = requireNotNull(database.station("noaa/9449880"))
        val at = Instant.parse("2026-07-15T00:00:00Z")
        val model = tideReference(harbor)
        assertTrue(model.heights(at, at).single().heightMetres.isFinite())
    }

    @Test fun packagesBinaryMetadataForTheHarmonicCurrentReference() {
        val database = StationDatabase.open(File("src/main/assets/slackwater/currents.tcdb"))
        val subordinate = requireNotNull(database.station("noaa/PCT3896"))
        val reference = requireNotNull(subordinate.current?.offsets?.reference)
        val harmonic = requireNotNull(database.station(reference))
        assertTrue(harmonic.constituents.isNotEmpty())
    }
}
