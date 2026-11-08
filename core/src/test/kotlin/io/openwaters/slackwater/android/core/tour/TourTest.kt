package io.openwaters.slackwater.android.core.tour

import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.core.timeline.TimelineDay
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class TourTest {
    private var seen = 0
    private val coach = TourCoach { seen++ }
    private val here = StationId("noaa/9447130")
    private val there = StationId("noaa/9444900")

    private fun walk(moonCardAvailable: Boolean = true): List<TourStep> = buildList {
        while (true) {
            add(coach.state.value.step ?: break)
            coach.advance(moonCardAvailable)
        }
    }

    @Test fun walksAllFiveStepsThenFinishes() {
        coach.arm(seen = false)
        coach.begin(here, starsAvailable = true, moonAvailable = true)
        assertEquals(TourStep.entries, walk())
        assertEquals(TourState(), coach.state.value)
        assertEquals(1, seen)
    }

    @Test fun skipsSkyStepsWhenThereIsNoNight() {
        coach.arm(seen = false)
        coach.begin(here, starsAvailable = false, moonAvailable = false)
        assertEquals(listOf(TourStep.READ, TourStep.MOON_CARD, TourStep.STAR), walk())
    }

    @Test fun skipsMoonDetailsWhenItsTileIsAbsent() {
        coach.arm(seen = false)
        coach.begin(here, starsAvailable = true, moonAvailable = true)
        assertEquals(listOf(TourStep.READ, TourStep.STARS, TourStep.MOON, TourStep.STAR),
            walk(moonCardAvailable = false))
        assertEquals(1, seen)
    }

    @Test fun skipsOnlyTheMoonWhenOnlyTheStarsAreReachable() {
        coach.arm(seen = false)
        coach.begin(here, starsAvailable = true, moonAvailable = false)
        assertEquals(listOf(TourStep.READ, TourStep.STARS, TourStep.MOON_CARD, TourStep.STAR), walk())
    }

    @Test fun aSeenTourNeverArmsAndArmsOncePerProcess() {
        coach.arm(seen = true)
        coach.begin(here, true, true)
        assertNull(coach.state.value.step)
        val fresh = TourCoach { seen++ }
        fresh.arm(seen = false)
        fresh.begin(here, true, true)
        fresh.finish()
        fresh.arm(seen = false)
        fresh.begin(here, true, true)
        assertNull(fresh.state.value.step)
        assertEquals(1, seen)
    }

    @Test fun onlyTheFirstDetailWithATimelineBeginsIt() {
        coach.arm(seen = false)
        coach.begin(here, true, true)
        coach.advance(moonCardAvailable = true)
        coach.begin(there, true, true)
        assertEquals(TourStep.STARS, coach.state.value.step)
        assertEquals(here, coach.state.value.station)
    }

    @Test fun aStrayFinishDoesNotMarkTheTourSeen() {
        coach.arm(seen = false)
        coach.finish()
        assertEquals(0, seen)
        coach.begin(here, true, true)
        assertEquals(TourStep.READ, coach.state.value.step)
    }

    @Test fun aHandedOffTourIsClaimedOnlyByTheLinkedStation() {
        coach.arm(seen = false)
        coach.begin(here, true, true)
        coach.advance(moonCardAvailable = true)
        coach.handOff(there)
        assertNull(coach.state.value.step)
        // The replaced detail leaving does not mark the tour seen, and it cannot claim the tour back.
        coach.finish()
        coach.begin(here, true, true)
        assertNull(coach.state.value.step)
        coach.begin(there, true, true)
        assertEquals(TourStep.READ, coach.state.value.step)
        assertEquals(there, coach.state.value.station)
        assertEquals(0, seen)
    }

    @Test fun aLinkReservesTheTourBeforeTheTourStationHasATimeline() {
        coach.arm(seen = false)
        coach.handOff(there)
        coach.begin(here, true, true)
        assertNull(coach.state.value.step)
        coach.begin(there, true, true)
        assertEquals(there, coach.state.value.station)
    }

    @Test fun aLinkToTheTourStationChangesNothing() {
        coach.arm(seen = false)
        coach.begin(here, true, true)
        coach.advance(moonCardAvailable = true)
        coach.handOff(here)
        assertEquals(TourStep.STARS, coach.state.value.step)
    }

    @Test fun aLinkAfterTheTourEndsChangesNothing() {
        coach.arm(seen = false)
        coach.begin(here, true, true)
        coach.finish()
        coach.handOff(there)
        coach.begin(there, true, true)
        assertNull(coach.state.value.step)
    }

    @Test fun aReplayArmsWhateverWasSeenAndAStandDownMarksNothing() {
        coach.arm(seen = true)
        coach.replay()
        coach.begin(here, true, true)
        assertEquals(TourStep.READ, coach.state.value.step)
        coach.standDown()
        assertEquals(TourState(), coach.state.value)
        assertEquals(0, seen)
    }

    private fun tide(id: String, latitude: Double, longitude: Double, kind: StationKind = StationKind.Tide) =
        StationIdentity(StationId(id), id, "", latitude, longitude, kind)

    @Test fun picksTheNearestTidePlaceInRange() {
        val stations = listOf(
            tide("noaa/9447130", 47.6026, -122.3393),
            tide("chs-nanaimo-harbour", 49.1659, -123.9401, StationKind.CanadianTide),
            tide("noaa/current", 49.17, -123.94, StationKind.Current),
        )
        assertEquals(StationId("chs-nanaimo-harbour"), tourStation(Coordinate(49.17, -123.94), stations))
        assertEquals(StationId("noaa/9447130"), tourStation(Coordinate(47.60, -122.34), stations))
    }

    @Test fun fallsBackToFridayHarbor() {
        val stations = listOf(tide("noaa/9447130", 47.6026, -122.3393))
        assertEquals(TOUR_FALLBACK_STATION, tourStation(null, stations))
        assertEquals(TOUR_FALLBACK_STATION, tourStation(Coordinate(0.0, -150.0), stations))
        // About 160 km south of Seattle is past the range.
        assertEquals(TOUR_FALLBACK_STATION, tourStation(Coordinate(46.16, -122.34), stations))
    }

    private val base = Instant.parse("2026-06-01T00:00:00Z")
    private fun h(hours: Long) = base.plusSeconds(hours * 3600)
    private val wide = h(-100)..h(200)
    private fun day(offset: Long, sunrise: Long? = null, sunset: Long? = null, moonrise: Long? = null, moonset: Long? = null) =
        TimelineDay(LocalDate.of(2026, 6, 1).plusDays(offset), sunrise?.let(::h), sunset?.let(::h), moonrise = moonrise?.let(::h), moonset = moonset?.let(::h))

    @Test fun starsTimeIsAnHourAfterTheNextSunset() {
        val days = listOf(day(0, sunrise = 6, sunset = 20), day(1, sunrise = 30, sunset = 44))
        assertEquals(h(21), tourStarsTime(days, h(12), wide))
        assertEquals(h(45), tourStarsTime(days, h(21), wide))
        assertNull(tourStarsTime(days, h(12), h(0)..h(20)))
        assertNull(tourStarsTime(listOf(TimelineDay(LocalDate.of(2026, 6, 1))), h(12), wide))
    }

    @Test fun moonTimeIsTheMiddleOfTheFirstDarkMoonSpan() {
        // Dark 20–30; moon up 22–40: the overlap is 22–30, so 26.
        val days = listOf(day(0, sunrise = 6, sunset = 20, moonrise = 22), day(1, sunrise = 30, sunset = 44, moonset = 40))
        assertEquals(h(26), tourMoonTime(days, h(12), wide))
        assertNull(tourMoonTime(days, h(12), h(0)..h(25)))
    }

    @Test fun moonTimeSkipsADaytimeMoon() {
        // The first moon is up 8–16, all daylight; the second, 46–56, overlaps the night 44–54.
        val days = listOf(day(0, sunrise = 6, sunset = 20, moonrise = 8, moonset = 16),
            day(1, sunrise = 30, sunset = 44, moonrise = 46), day(2, sunrise = 54, moonset = 56))
        assertEquals(h(50), tourMoonTime(days, h(0), wide))
        assertNull(tourMoonTime(listOf(day(0, moonrise = 1, moonset = 5)), h(0), wide))
    }

    @Test fun starsAndMoonTargetsDropIndependently() {
        val days = listOf(day(0, sunrise = 6, sunset = 20), day(1, sunrise = 30))
        assertTrue(tourStarsTime(days, h(12), wide) != null)
        assertFalse(tourMoonTime(days, h(12), wide) != null)
    }
}
