package io.openwaters.slackwater.android.core.station

import org.junit.Assert.*
import org.junit.Test

class StationResolutionTest {
    private val current = StationId("current:noaa/A:B")
    private val old = StationId("old")
    private val middle = StationId("middle")
    private val row = StationIdentity(current, "Station", "", 1.0, 2.0, StationKind.Current)

    @Test fun resolvesChainsAndAccumulatesOnlyKnownShifts() {
        fun resolve(firstShift: Double?) = StationIdentityIndex(
            listOf(row), mapOf(old to StationSuccessor(middle, firstShift), middle to StationSuccessor(current, -0.5)),
        ).resolve(old) as StationResolution.Successor
        assertEquals(current, resolve(1.0).id)
        assertEquals(0.5, resolve(1.0).datumShiftMetres!!, 1e-9)
        assertNull(resolve(null).datumShiftMetres)
    }

    @Test fun onlyLiveStationsAreBrowsableAndReturningStationsResolveAsLive() {
        val retired = row.copy(id = old, kind = StationKind.Retired)
        val restricted = row.copy(id = middle, kind = StationKind.Restricted)
        val index = StationIdentityIndex(
            listOf(row.copy(kind = StationKind.Retired), retired, restricted, row),
            mapOf(current to StationSuccessor(old)),
        )
        assertEquals(listOf(row), index.identities)
        assertTrue(index.resolve(current) is StationResolution.Live)
        assertTrue(index.resolve(old) is StationResolution.Tombstone)
        assertTrue(index.resolve(middle) is StationResolution.Restricted)
    }

    @Test fun preservesSavedIdForCyclesAndMissingTargets() {
        for (edges in listOf(mapOf(old to StationSuccessor(middle)), mapOf(old to StationSuccessor(middle), middle to StationSuccessor(old)))) {
            val result = StationIdentityIndex(emptyList(), edges).resolve(old)
            assertTrue(result is StationResolution.Unknown)
            assertEquals(old, result.id)
        }
    }
}
