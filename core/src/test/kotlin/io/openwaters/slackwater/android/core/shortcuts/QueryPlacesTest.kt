package io.openwaters.slackwater.android.core.shortcuts

import io.openwaters.slackwater.android.core.station.*
import org.junit.Assert.assertEquals
import org.junit.Test

class QueryPlacesTest {
    private val oak = StationIdentity(StationId("oak"), "Oak Bay", "BC", 48.0, -123.0, StationKind.Tide)
    private val namesake = oak.copy(id = StationId("other"), latitude = 49.0)
    private val bay = oak.copy(id = StationId("bay"), name = "Bay", latitude = 50.0)
    private val region = oak.copy(id = StationId("region"), name = "Shore", region = "Bay")
    private val alias = oak.copy(id = StationId("alias"), name = "Beach", aliases = listOf("Bay"))
    private val current = oak.copy(id = StationId("current"), kind = StationKind.Current)
    private val gate = current.copy(id = StationId("gate"), kind = StationKind.DerivedGate)
    private val catalog = listOf(oak, namesake, bay, region, alias, current, gate)
    private fun named(fix: Coordinate?, favorites: List<StationId>, recents: List<StationId>) =
        namedQueryPlaces(catalog, StationSeries.Tide, " bAy ", fix, favorites, recents, setOf(namesake.id))

    @Test fun chosenLocalNamesakePrecedesFirstMatchingFavoriteThenMostRecent() {
        val favorites = listOf(current.id, alias.id, region.id)
        assertEquals(QueryPlaces.Candidates(listOf(namesake)), named(oak.coordinate, favorites, listOf(bay.id)))
        assertEquals(QueryPlaces.Candidates(listOf(alias)), named(null, favorites, listOf(bay.id)))
        assertEquals(QueryPlaces.Candidates(listOf(region)), named(null, listOf(current.id), listOf(region.id, oak.id)))
    }

    @Test fun catalogRanksWithoutForcingAnArbitraryMatch() {
        assertEquals(QueryPlaces.Ambiguous(listOf(bay, oak, namesake, region, alias)),
            named(null, emptyList(), emptyList()))
    }

    @Test fun nearbyNeedsRealFixAndSkipsTimingOnlyGates() {
        assertEquals(QueryPlaces.NeedsLocation, nearbyQueryPlaces(catalog, StationSeries.Current, null))
        assertEquals(QueryPlaces.Candidates(listOf(current)), nearbyQueryPlaces(catalog, StationSeries.Current, oak.coordinate))
        assertEquals(QueryPlaces.Missing, selectedQueryPlace(gate, StationSeries.Current))
        assertEquals(QueryPlaces.Missing, selectedQueryPlace(oak, StationSeries.Current))
    }
}
