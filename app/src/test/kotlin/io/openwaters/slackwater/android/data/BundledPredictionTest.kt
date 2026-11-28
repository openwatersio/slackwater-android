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

    @Test fun predictsPackagedTideLikeTheTypeScriptEngine() {
        val database = StationDatabase.open(File("src/main/assets/slackwater/stations.tcdb"))
        val harbor = requireNotNull(database.station("noaa/9449880"))
        val at = Instant.parse("2026-12-19T12:00:00Z")
        val model = tideReference(harbor)
        // Slackwater's TypeScript engine at the pinned revision, with the bundled station inputs.
        assertEquals(1.6079792021908887, model.heights(at, at).single().heightMetres, 1e-6)
    }

    @Test fun engineAcceptsEveryPackagedTideModel() {
        val database = StationDatabase.open(File("src/main/assets/slackwater/stations.tcdb"))
        val groups = database.groupBy { it.tideOffsets?.reference ?: it.id }
        for ((referenceId, rows) in groups) {
            try {
                val reference = tideReference(requireNotNull(database.station(referenceId)))
                for (row in rows) {
                    if (row.tideOffsets != null) tideSubordinate(row, reference)
                }
            } catch (error: IllegalArgumentException) {
                throw AssertionError("Invalid packaged tide model using reference $referenceId", error)
            }
        }
    }

    @Test fun packagesBinaryMetadataForTheHarmonicCurrentReference() {
        val database = StationDatabase.open(File("src/main/assets/slackwater/currents.tcdb"))
        val subordinate = requireNotNull(database.station("noaa/PCT3896"))
        val reference = requireNotNull(subordinate.current?.offsets?.reference)
        val harmonic = requireNotNull(database.station(reference))
        assertTrue(harmonic.constituents.isNotEmpty())
    }
}
