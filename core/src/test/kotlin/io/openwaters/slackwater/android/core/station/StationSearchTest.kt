package io.openwaters.slackwater.android.core.station

import org.junit.Assert.*
import org.junit.Test

class StationSearchTest {
    private val anchor = Coordinate(48.0, -123.0)

    private fun station(id: String, name: String, region: String, north: Double, kind: StationKind = StationKind.Tide, aliases: List<String> = emptyList()) =
        StationIdentity(StationId(id), name, region, anchor.latitude + north, anchor.longitude, kind, aliases)

    private val List<RankedStation>.ids get() = map { it.id.value }

    @Test fun nameMatchesRankBeforeRegionThenAliasThenDistance() {
        // §14.14
        val catalog = listOf(
            station("alias", "Elsewhere", "Nowhere", 0.01, aliases = listOf("Port Townsend ferry")),
            station("region-near", "Point Wilson", "Port Townsend", 0.02),
            station("name-far", "Port Townsend", "WA", 0.09),
            station("name-near", "Port Townsend Marina", "WA", 0.05),
            station("other", "Seattle", "WA", 0.0),
        )
        val results = StationSearch(catalog).search("port townsend", anchor)
        assertEquals(listOf("name-near", "name-far", "region-near", "alias"), results.stations.ids)
        assertFalse(results.truncated)
    }

    @Test fun queriesAreTrimmedLowercasedAndMatchedAsPlainSubstrings() {
        val catalog = listOf(station("a", "Île d'Orléans", "QC", 0.0), station("b", "Orleans Bay", "LA", 0.01))
        val search = StationSearch(catalog)
        assertEquals(listOf("a"), search.search(" \tORLÉANS ", anchor).stations.ids)
        // No diacritic folding: the unaccented query finds only the unaccented name.
        assertEquals(listOf("b"), search.search("orleans", anchor).stations.ids)
        assertEquals(listOf("a", "b"), search.search("an", anchor).stations.ids)
        assertEquals(emptyList<String>(), search.search("line\nbreak", anchor).stations.ids)
    }

    @Test fun anEmptyQueryReturnsTheNearestSixtyAndSaysSo() {
        // §14.13
        val catalog = (0 until 100).map { station("noaa/$it", "Station $it", "WA", (100 - it) * 0.001) }
        val results = StationSearch(catalog).search("   ", anchor)
        assertEquals(SEARCH_RESULT_LIMIT, results.stations.size)
        assertTrue(results.truncated)
        assertEquals("noaa/99", results.stations.first().id.value)
        assertEquals(results.stations.sortedBy { it.distanceKm }.ids, results.stations.ids)
    }

    @Test fun theSeriesFilterFillsTheLimitWithThatSeries() {
        val tides = (0 until 70).map { station("noaa/$it", "Harbor $it", "WA", it * 0.001) }
        val currents = (0 until 5).map { station("current:noaa/$it", "Harbor current $it", "WA", 1 + it * 0.001, StationKind.Current) }
        val search = StationSearch(tides + currents)
        assertEquals(currents.map { it.id.value }, search.search("harbor", anchor, StationSeries.Current).stations.ids)
        val tideResults = search.search("harbor", anchor, StationSeries.Tide)
        assertEquals(SEARCH_RESULT_LIMIT, tideResults.stations.size)
        assertTrue(tideResults.stations.all { it.identity.series == StationSeries.Tide })
    }

    @Test fun namesakesAreSeparateResultsAndTiesBreakById() {
        val catalog = listOf(station("noaa/b", "Bay", "North", 0.01), station("noaa/a", "Bay", "South", 0.01))
        assertEquals(listOf("noaa/a", "noaa/b"), StationSearch(catalog).search("bay", anchor).stations.ids)
    }

    @Test fun composedAndDecomposedTextMatch() {
        val decomposed = station("d", "Orle\u0301ans Point", "QC", 0.0)
        val search = StationSearch(listOf(decomposed))
        assertEquals(listOf("d"), search.search("orl\u00e9ans", anchor).stations.ids)
        assertEquals(listOf("d"), search.search("ORLE\u0301ANS", anchor).stations.ids)
    }

    @Test fun retiredAndRestrictedRowsAreNeverResults() {
        val catalog = listOf(
            station("live", "Bay", "WA", 0.0),
            station("retired", "Bay", "WA", 0.0, StationKind.Retired),
            station("restricted", "Bay", "WA", 0.0, StationKind.Restricted),
        )
        assertEquals(listOf("live"), StationSearch(catalog).search("bay", anchor).stations.ids)
    }

    @Test fun noMatchesIsEmpty() {
        val results = StationSearch(listOf(station("a", "Bay", "WA", 0.0))).search("zzz", anchor)
        assertTrue(results.stations.isEmpty())
        assertFalse(results.truncated)
    }
}
