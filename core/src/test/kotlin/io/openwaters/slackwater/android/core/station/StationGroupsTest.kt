package io.openwaters.slackwater.android.core.station

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StationGroupsTest {
    // About 1.11 km per 0.01° of latitude, so offsets north of the fix rank in the order written.
    private val fix = Coordinate(48.0, -123.0)

    private fun station(id: String, name: String, north: Double, kind: StationKind = StationKind.Tide, region: String = "WA") =
        StationIdentity(StationId(id), name, region, fix.latitude + north, fix.longitude, kind)

    private val harbor = station("noaa/1", "Harbor", 0.01)
    private val narrows = station("current:noaa/N1", "Narrows", 0.02, StationKind.Current)
    private val bay = station("noaa/2", "Bay", 0.03)
    private val bayFar = station("noaa/3", "Bay", 0.05)
    private val point = station("noaa/4", "Point", 0.06)
    private val pass = station("current:noaa/P1", "Pass", 0.07, StationKind.Current)
    private val inlet = station("noaa/5", "Inlet", 0.08)
    private val cove = station("noaa/6", "Cove", 0.09)
    private val sound = station("noaa/7", "Sound", 0.10)
    private val catalog = listOf(bay, bayFar, cove, harbor, inlet, narrows, pass, point, sound)

    private fun sections(
        fix: Coordinate? = this.fix,
        favorites: List<StationId> = emptyList(),
        recents: List<StationId> = emptyList(),
        chosen: Set<StationId> = emptySet(),
        filter: StationSeries? = null,
    ) = stationListSections(catalog, fix, favorites, recents, chosen, filter)

    private val List<RankedStation>.ids get() = map { it.id }

    @Test fun groupOrderWithAFixAFavoriteAndARecent() {
        // §14.1: My Location, Favorites, Near Me, Recents, each station once.
        val s = sections(favorites = listOf(sound.id), recents = listOf(cove.id))
        assertEquals(listOf(harbor.id, narrows.id), s.heroes.ids)
        assertEquals(listOf(sound.id), s.favorites)
        assertEquals(listOf(bay.id, point.id, pass.id, inlet.id), s.nearMe.ids)
        assertEquals(listOf(cove.id), s.recents)
        assertEquals(RankingAnchor.Fix(fix), s.anchor)
    }

    @Test fun eachStationHasOneHomeAndSavedListsStayIntact() {
        // §14.2: a hero that is a favorite shows only as a hero; a favorite that is recent shows only in Favorites.
        val favorites = listOf(harbor.id, bay.id)
        val recents = listOf(bay.id, sound.id)
        val s = sections(favorites = favorites, recents = recents)
        assertEquals(listOf(harbor.id, narrows.id), s.heroes.ids)
        assertEquals(listOf(bay.id), s.favorites)
        assertFalse(bay.id in s.nearMe.ids)
        assertEquals(listOf(sound.id), s.recents)
        assertEquals(listOf(harbor.id, bay.id), favorites)
        // Unstarring the hero restores nothing that was not stored.
        assertEquals(listOf(bay.id), sections(favorites = listOf(bay.id), recents = recents).favorites)
    }

    @Test fun namesakesCollapseInNearMeAndTheChoiceWins() {
        // §14.3: two Bays show once, as the nearer, until the farther is chosen.
        val nearest = sections()
        assertEquals(1, nearest.nearMe.count { it.identity.name == "Bay" })
        assertTrue(bay.id in nearest.nearMe.ids)
        val chosen = sections(chosen = setOf(bayFar.id))
        assertEquals(listOf(bayFar.id), chosen.nearMe.filter { it.identity.name == "Bay" }.ids)
        // The chosen namesake keeps the nearer one's place in the order but shows its own distance.
        assertEquals(nearest.nearMe.ids.indexOf(bay.id), chosen.nearMe.ids.indexOf(bayFar.id))
        assertEquals(distanceKm(fix, bayFar.coordinate), chosen.nearMe.single { it.id == bayFar.id }.distanceKm, 1e-12)
    }

    @Test fun aTideAndACurrentWithTheSameNameStaySeparate() {
        val tide = station("noaa/9", "Gap", 0.01)
        val current = station("current:noaa/G9", "Gap", 0.02, StationKind.Current)
        val shown = Namesakes(rankByDistance(listOf(tide, current), fix), emptySet()).shown
        assertEquals(listOf(tide.id, current.id), shown.ids)
    }

    @Test fun seriesFilterNarrowsNearMeOnly() {
        // §14.4: Currents leaves heroes, favorites and recents alone.
        val s = sections(favorites = listOf(sound.id), recents = listOf(cove.id), filter = StationSeries.Current)
        assertEquals(listOf(harbor.id, narrows.id), s.heroes.ids)
        assertEquals(listOf(pass.id), s.nearMe.ids)
        assertEquals(listOf(sound.id), s.favorites)
        assertEquals(listOf(cove.id), s.recents)
        assertEquals(listOf(bay.id, point.id, inlet.id, cove.id), sections(filter = StationSeries.Tide).nearMe.ids)
    }

    @Test fun withoutAFixTheLastOpenedPlaceAnchorsFiveCards() {
        // §14.5 and §14.7: no heroes, five Near Me cards ranked from the last-opened place.
        val s = sections(fix = null, recents = listOf(StationId("gone/1"), sound.id))
        assertEquals(RankingAnchor.LastOpened(sound), s.anchor)
        assertTrue(s.heroes.isEmpty())
        assertEquals(NEAR_ME_COUNT_WITHOUT_FIX, s.nearMe.size)
        assertEquals(sound.id, s.nearMe.first().id)
        assertEquals(0.0, s.nearMe.first().distanceKm, 0.0)
        // A recent that left the catalog neither anchors nor renders.
        assertEquals(emptyList<StationId>(), s.recents)
    }

    @Test fun firstLaunchRanksFromAnnapolis() {
        // §14.8
        val s = sections(fix = null)
        assertEquals(RankingAnchor.FirstRun, s.anchor)
        assertEquals(FIRST_RUN_ANCHOR, s.anchor.coordinate)
        assertEquals(NEAR_ME_COUNT_WITHOUT_FIX, s.nearMe.size)
    }

    @Test fun aSecondHeroOnlyWithinTheCrossSeriesRadius() {
        // §14.9
        val tide = station("noaa/h", "Here", 0.0)
        val nearCurrent = station("current:noaa/h", "Close", 0.17, StationKind.Current)
        val farCurrent = station("current:noaa/f", "Far", 0.19, StationKind.Current)
        assertEquals(listOf(tide.id, nearCurrent.id), heroes(rankByDistance(listOf(tide, nearCurrent), fix)).ids)
        assertEquals(listOf(tide.id), heroes(rankByDistance(listOf(tide, farCurrent), fix)).ids)
        assertTrue(distanceKm(fix, nearCurrent.coordinate) <= HERO_CROSS_SERIES_RADIUS_KM)
        assertTrue(distanceKm(fix, farCurrent.coordinate) > HERO_CROSS_SERIES_RADIUS_KM)
        assertEquals(emptyList<RankedStation>(), heroes(emptyList()))
    }

    @Test fun aHeroStandsInForItsChosenNamesake() {
        val s = sections(fix = bay.coordinate, chosen = setOf(bayFar.id))
        assertEquals(bayFar.id, s.heroes.first().id)
        assertFalse(bayFar.id in s.nearMe.ids)
    }

    @Test fun retiredAndRestrictedRowsNeverRank() {
        val retired = station("noaa/r", "Retired", 0.001).copy(kind = StationKind.Retired)
        val s = stationListSections(catalog + retired, fix, emptyList(), listOf(retired.id), emptySet(), null)
        assertFalse(retired.id in s.nearMe.ids + s.heroes.ids)
        assertEquals(emptyList<StationId>(), s.recents)
        assertEquals(RankingAnchor.Fix(fix), s.anchor)
    }

    @Test fun removedFavoritesKeepTheirSlot() {
        val gone = StationId("chs-withdrawn")
        assertEquals(listOf(sound.id, gone, cove.id), sections(favorites = listOf(sound.id, gone, cove.id)).favorites)
    }

    @Test fun equalDistancesKeepCatalogOrder() {
        val a = station("b/2", "Alpha", 0.01)
        val b = station("a/1", "Beta", 0.01)
        assertEquals(listOf(a.id, b.id), rankByDistance(listOf(a, b), fix).ids)
        assertEquals(listOf(b.id, a.id), rankByDistance(listOf(b, a), fix).ids)
    }

    @Test fun groupsDedupeByPriority() {
        val (a, b, c, d) = listOf("a", "b", "c", "d").map(::StationId)
        val groups = ListGroups.of(heroes = listOf(a), favorites = listOf(a, b), recents = listOf(c, b, d), ranked = listOf(a, b, c), nearCount = 4)
        assertEquals(ListGroups(listOf(a), listOf(b), listOf(c), listOf(d)), groups)
    }

    @Test fun haversineMatchesKnownDistances() {
        assertEquals(0.0, distanceKm(fix, fix), 0.0)
        // One degree of latitude on the 6371.0088 km sphere.
        assertEquals(111.19508, distanceKm(Coordinate(0.0, 0.0), Coordinate(1.0, 0.0)), 1e-5)
        // Antipodes clamp rather than producing NaN.
        assertEquals(Math.PI * 6371.0088, distanceKm(Coordinate(0.0, 0.0), Coordinate(0.0, 180.0)), 1e-6)
    }

    @Test fun nauticalMilesUseOneDecimalBelowTen() {
        fun nm(value: Double, locale: Locale = Locale.US) = formatNauticalMiles(value * 1.852, locale)
        assertEquals("1.2", nm(1.2))
        assertEquals("0.0", nm(0.04))
        assertEquals("0.1", nm(0.05))
        assertEquals("9.9", nm(9.94))
        // Rounded after the choice of places, as iOS does.
        assertEquals("10.0", nm(9.97))
        assertEquals("14", nm(14.4))
        assertEquals("15", nm(14.5))
        assertEquals("12345", nm(12345.0))
        assertEquals("1,2", nm(1.2, Locale.GERMANY))
        // The shortest decimal form rounds, not the binary value just below the half.
        assertEquals("0.2", formatNauticalMiles(0.15 * 1.852, Locale.US).also { assertEquals(0.15, 0.15 * 1.852 / 1.852, 0.0) })
        assertEquals("1.4", formatNauticalMiles(1.35 * 1.852, Locale.US))
    }
}
