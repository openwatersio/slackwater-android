package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.stations.StationListState
import org.junit.Assert.*
import org.junit.Test

class PhoneSelectionTest {
    private val automatic = PhoneSelection(0, false)

    @Test fun automaticRetriesBackOffIndependentlyAndWakeOnReconnect() {
        val retries = AutomaticFitRetries()
        retries.observeRecovery(0)
        val a = StationId("a")
        val b = StationId("b")
        for (delay in listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)) {
            retries.defer(a, 100L)
            assertEquals(100L + delay, retries.due(a))
        }
        assertEquals(0L, retries.due(b))
        retries.defer(b, 200L)
        assertEquals(60_200L, retries.due(b))
        retries.observeRecovery(0)
        assertEquals(60_200L, retries.due(b))
        retries.observeRecovery(1)
        assertEquals(0L, retries.due(a))
        assertEquals(0L, retries.due(b))
        retries.defer(a, 300L)
        assertEquals(60_300L, retries.due(a))
    }

    @Test fun radiusChoicesAreInclusiveAndIndependentFromCurrents() {
        assertFalse(automatic.backgroundRequested)
        val currents = automatic.copy(allCurrents = true)
        assertTrue(currents.backgroundRequested)
        val wide = currents.withRadius(150, true)
        assertEquals(wide, wide.withRadius(25, true))
        assertEquals(currents.copy(radiusKm = 25), wide.withRadius(150, false))
        assertEquals(currents, wide.withRadius(25, false))
    }

    @Test fun radiusAddsCanadianStationsAndKeepsDistantFavoritesWithoutAFix() {
        fun station(id: String, longitude: Double, kind: StationKind = StationKind.CanadianTide) =
            StationIdentity(StationId(id), id, "", 0.0, longitude, kind)
        val near = station("near", 0.2)
        val far = station("far", 1.0)
        val gate = station("gate", 8.0, StationKind.DerivedGate)
        val favorite = station("favorite", 5.0)
        val bundled = station("bundled", 0.0, StationKind.Tide)
        val origin = Coordinate(0.0, 0.0)
        val sections = StationListSections(RankingAnchor.Fix(origin), emptyList(), listOf(favorite.id), emptyList(), emptyList())
        val state = StationListState(sections, listOf(near, far, gate, favorite, bundled).associateBy { it.id },
            LocationSlot.Located(LocationFix(origin, 1.0, 0), false), emptyMap(), setOf(favorite.id), null, null)
        assertEquals(listOf(favorite.id), automatic.stations(state))
        assertEquals(listOf(near.id, favorite.id), PhoneSelection(25, false).stations(state))
        assertEquals(listOf(near.id, far.id, favorite.id), PhoneSelection(150, false).stations(state))
        assertEquals(setOf(favorite.id, gate.id), PhoneSelection(150, true).stations(state.copy(slot = LocationSlot.Denied)).toSet())
    }
}
