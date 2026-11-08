package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.*

class CalendarPlanTest {
    private val now = Instant.parse("2026-08-10T12:00:00Z")
    private val zone = ZoneId.of("America/Vancouver")
    private val station = StationIdentity(StationId("noaa:test"), "Friday Harbor", "", 48.54, -123.01,
        StationKind.Tide, timezone = zone.id)
    private var requested: TimeSpan? = null

    private fun events(
        series: PredictionSeries, capability: StationCapabilities, kind: StationKind,
        threshold: Double = 0.5, imperial: Boolean = false, start: Instant = now, partial: Boolean = false, timezone: String? = zone.id,
    ): List<StationCalendarEvent>? {
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
                assertEquals(PredictionPurpose.CALENDAR, purpose)
                assertEquals(station.id, id)
                requested = span
                return PredictionResult.Ready(id, if (partial) TimeSpan(span.start, span.start) else span,
                    "fixture", capability, series)
            }
        }
        val call = suspend { stationCalendarEvents(station.copy(kind = kind, timezone = timezone), start, threshold, imperial, repository) }
        var answer: Result<List<StationCalendarEvent>?>? = null
        call.startCoroutine(object : Continuation<List<StationCalendarEvent>?> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<List<StationCalendarEvent>?>) { answer = result }
        })
        return requireNotNull(answer).getOrThrow()
    }

    @Test fun tidesAreFutureThroughNinetyDaysWithLocalNotesAndRequestedUnits() {
        val end = now.plus(CALENDAR_HORIZON)
        val turns = listOf(TideExtreme(now, 2.0, ExtremeKind.HIGH),
            TideExtreme(now.plusSeconds(60), 1.0, ExtremeKind.HIGH),
            TideExtreme(end, -0.6, ExtremeKind.LOW), TideExtreme(end.plusSeconds(60), 0.8, ExtremeKind.HIGH))
        val series = PredictionSeries.Tide(emptyList(), emptyList(), turns)
        val rows = events(series, StationCapabilities.HARMONIC_TIDE, StationKind.Tide)!!.filter { it.title.contains("tide") }
        assertEquals(TimeSpan(now, end), requested)
        assertEquals(listOf(CalendarEventKind.HIGH_TIDE, CalendarEventKind.LOW_TIDE), rows.map { it.kind })
        assertEquals(listOf(1.0, -0.6), rows.map { it.heightM })
        assertEquals(listOf("High tide 1.00 m", "Low tide -0.60 m"), rows.map { it.title })
        assertEquals("05:01 · 1.00 m", rows.first().notes)
        assertEquals(listOf(now.plusSeconds(60), end), rows.map { it.start })
        assertTrue(rows.all { it.end == it.start && it.stationId == station.id && it.zone == zone })
        val feet = events(series, StationCapabilities.FITTED_TIDE, StationKind.CanadianTide, imperial = true)!!
        assertTrue(feet.any { it.title == "High tide 3.3 ft" })
    }

    @Test fun unrecognizedStationZoneFallsBackToUtc() {
        val series = PredictionSeries.Tide(emptyList(), emptyList(), listOf(TideExtreme(now.plusSeconds(60), 1.0, ExtremeKind.HIGH)))
        val rows = events(series, StationCapabilities.HARMONIC_TIDE, StationKind.Tide, timezone = "Unknown/Station")!!
        assertEquals("12:01 · 1.00 m", rows.single { it.title.startsWith("High tide") }.notes)
    }

    @Test fun currentCalendarPublishesOnlyWindowOpenToCloseAndOffPublishesAnInstant() {
        val times = listOf(600L, 1200L, 1800L).map(now::plusSeconds)
        val series = PredictionSeries.Current(times.zip(listOf(-1.0, 0.0, 1.0)) { time, speed -> CurrentPoint(time, speed) },
            listOf(CurrentEvent(times.first(), -1.0, CurrentEventKind.MAX_EBB),
                CurrentEvent(times[1], 0.0, CurrentEventKind.SLACK), CurrentEvent(times.last(), 1.0, CurrentEventKind.MAX_FLOOD)),
            null, null)
        val window = events(series, StationCapabilities.FINAL_CURRENT, StationKind.CanadianCurrent)!!.single { it.title != "Lunar eclipse" }
        assertEquals("Slack window", window.title)
        assertEquals(CalendarEventKind.SLACK_WINDOW, window.kind)
        assertEquals(window.end, window.windowEnd)
        assertEquals(0.5, requireNotNull(window.thresholdKnots), 0.0)
        assertEquals(now.plusSeconds(900), window.start)
        assertEquals(now.plusSeconds(1500), window.end)
        assertEquals("05:15–05:25, under 0.5 kn", window.notes)
        val off = events(series, StationCapabilities.HARMONIC_CURRENT, StationKind.Current, threshold = 0.0)!!
            .single { it.title != "Lunar eclipse" }
        assertEquals("Slack", off.title)
        assertEquals(CalendarEventKind.SLACK, off.kind)
        assertNull(off.thresholdKnots)
        assertEquals(times[1], off.start)
        assertEquals(off.start, off.end)
        assertEquals("05:20", off.notes)
    }

    @Test fun derivedCalendarIncludesPlainSlacksAndVisibleEclipseFirstContact() {
        val start = Instant.parse("2026-03-01T00:00:00Z")
        val turn = start.plusSeconds(7200)
        val derived = PredictionSeries.Derived(listOf(PredictionSeries.DerivedSlack(turn, ExtremeKind.HIGH)))
        val rows = events(derived, StationCapabilities.DERIVED_TIMING, StationKind.DerivedGate, start = start)!!
        val eclipse = io.openwaters.almanac.lunarEclipses(start, start.plus(CALENDAR_HORIZON)).first().u1!!
            .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        assertEquals(listOf("Slack", "Lunar eclipse"), rows.map { it.title })
        assertEquals(listOf(turn, eclipse), rows.map { it.start })
        assertEquals(listOf(CalendarEventKind.SLACK, CalendarEventKind.LUNAR_ECLIPSE), rows.map { it.kind })
        assertTrue(rows.all { it.end == it.start })
        assertEquals("Friday Harbor Currents", stationCalendarTitle(station.copy(kind = StationKind.DerivedGate)))
        assertEquals("Friday Harbor Tides", stationCalendarTitle(station))
    }

    @Test fun temporaryUnavailableAndPartialCoverageNeverMeanPublishAnEmptyCalendar() {
        val tide = PredictionSeries.Tide(emptyList(), emptyList(), emptyList())
        val current = PredictionSeries.Current(emptyList(), emptyList(), null, null)
        assertNull(events(tide, StationCapabilities.TIDE_PREVIEW, StationKind.CanadianTide))
        assertNull(events(current, StationCapabilities.ONLINE_CURRENT, StationKind.CanadianCurrent))
        assertNull(events(tide, StationCapabilities.HARMONIC_TIDE, StationKind.Tide, partial = true))
        val oneHour = PredictionResult.Ready(station.id, TimeSpan(now, now.plusSeconds(3600)), "fit",
            StationCapabilities.FINAL_CURRENT, current)
        assertFalse(oneHour.coversCalendar(now))
        assertTrue(oneHour.copy(coverage = TimeSpan(now, now.plus(CALENDAR_HORIZON))).coversCalendar(now))
        assertNull(stationCalendarTitle(station.copy(kind = StationKind.Restricted)))
    }
}
