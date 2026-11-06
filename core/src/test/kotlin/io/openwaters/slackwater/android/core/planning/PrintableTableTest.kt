package io.openwaters.slackwater.android.core.planning

import io.openwaters.almanac.Observer
import io.openwaters.almanac.lunarEclipses
import io.openwaters.almanac.solarEclipses
import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import kotlin.coroutines.*

class PrintableTableTest {
    private val station = StationIdentity(StationId("noaa/9449880"), "Friday Harbor", "WA", 48.5, -123.0,
        StationKind.Tide, timezone = "America/Los_Angeles")
    private val anchor = Instant.parse("2025-11-05T12:00:00Z")
    private fun make(
        station: StationIdentity = this.station, span: PrintSpan = PrintSpan.MONTH, anchor: Instant = this.anchor,
        load: (TimeSpan) -> PredictionResult,
    ): PrintableTable {
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
                assertEquals(PredictionPurpose.PRINT, purpose)
                assertEquals(station.id, id)
                return load(span)
            }
        }
        val call = suspend { printableTable(station, span, anchor, anchor, repository, "MLLW", "https://example.org/station",
            "Kartverket", "Point Atkinson", attribution = "Station attribution") }
        var answer: Result<PrintableTable>? = null
        call.startCoroutine(object : Continuation<PrintableTable> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<PrintableTable>) { answer = result }
        })
        return requireNotNull(answer).getOrThrow()
    }

    private fun ready(span: TimeSpan, series: PredictionSeries, capability: StationCapabilities) =
        PredictionResult.Ready(station.id, span, "fixture", capability, series)

    @Test fun weekStartsAtAnchorLocalMidnightAndMonthUsesCivilDays() {
        val zone = ZoneId.of("America/Los_Angeles")
        val beforeFallBack = Instant.parse("2025-11-01T12:00:00Z")
        val week = printRange(PrintSpan.WEEK, beforeFallBack, zone)
        assertEquals(Instant.parse("2025-11-01T07:00:00Z"), week.start)
        assertEquals(169L, Duration.between(week.start, week.end).toHours())
        val march = printRange(PrintSpan.MONTH, Instant.parse("2025-03-18T00:00:00Z"), zone)
        assertEquals(743L, Duration.between(march.start, march.end).toHours())
    }

    @Test fun partialMonthOnlineBlocksPreserveGapsLocalValuesSetsAndBareSlacks() {
        val gate = station.copy(kind = StationKind.CanadianCurrent, id = StationId("chs-current"))
        fun block(start: String, end: String, flood: Double): PredictionResult.Ready {
            val s = TimeSpan(Instant.parse(start), Instant.parse(end))
            val row = s.end.minusSeconds(7200)
            val events = listOf(CurrentEvent(row, -2.3, CurrentEventKind.MAX_EBB),
                CurrentEvent(row.plusSeconds(600), 0.0, CurrentEventKind.SLACK),
                CurrentEvent(row.plusSeconds(1200), 1.8, CurrentEventKind.MAX_FLOOD))
            return ready(s, PredictionSeries.Current(emptyList(), events, flood, 155.0), StationCapabilities.ONLINE_CURRENT)
        }
        val table = make(gate) {
            PredictionResult.Disjoint(listOf(block("2025-10-28T12:00:00Z", "2025-11-03T12:00:00Z", 355.0),
                block("2025-11-09T06:00:00Z", "2025-11-10T12:00:00Z", 350.0),
                block("2025-12-12T12:00:00Z", "2025-12-13T12:00:00Z", 350.0)))
        }
        assertEquals(listOf(LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 10)), table.days.map { it.date })
        assertEquals(2, table.covered.size)
        assertEquals(table.range.start, table.covered.first().start)
        assertEquals("CHS", table.source)
        val day = table.days.first()
        assertNotNull(day.sunrise)
        assertNotNull(day.sunset)
        assertTrue(day.entries.all { it.night })
        assertEquals(2.3, day.entries[0].speedKnots!!, 0.0)
        assertEquals(155.0, day.entries[0].setDegrees!!, 0.0)
        assertNull(day.entries[1].speedKnots)
        assertEquals(355.0, day.entries[2].setDegrees!!, 0.0)
        assertNull(table.floodDegrees)
        assertEquals(155.0, table.ebbDegrees!!, 0.0)
    }

    @Test fun previewTideDeduplicatesAndDropsTurnsOutsideStoredCoverageAndRange() {
        val place = station.copy(kind = StationKind.CanadianTide)
        val table = make(place) { range ->
            val end = range.start.plusSeconds(2 * 86400L)
            val event = TideExtreme(range.start.plusSeconds(3600), 1.4, ExtremeKind.HIGH)
            val series = PredictionSeries.Tide(emptyList(), emptyList(), listOf(event, event,
                TideExtreme(range.start.minusSeconds(1), 0.0, ExtremeKind.LOW),
                TideExtreme(end.plusSeconds(1), 1.3, ExtremeKind.HIGH)))
            ready(TimeSpan(range.start.minusSeconds(86400), end), series, StationCapabilities.TIDE_PREVIEW)
        }
        assertEquals(table.range.start, table.covered.single().start)
        assertEquals(1.4, table.days.single().entries.single().heightM!!, 0.0)
        assertEquals("MLLW", table.datum)
        assertEquals("Station attribution", table.attribution)
        val unavailable = make(place) { PredictionResult.Unavailable(PredictionResult.Reason.OUTSIDE_COVERAGE) }
        assertTrue(unavailable.covered.isEmpty() && unavailable.days.isEmpty())
        assertEquals(PredictionResult.Reason.OUTSIDE_COVERAGE, unavailable.unavailableReason)
    }

    @Test fun boundedPolarRowsUseTheSunAtTheirOwnInstantWithoutARiseOrSet() {
        val andenes = station.copy(latitude = 69.315, longitude = 16.12, timezone = "Europe/Oslo")
        for ((anchored, night) in listOf("2025-12-14T00:00:00Z" to true, "2025-06-14T00:00:00Z" to false)) {
            val time = Instant.parse(anchored)
            val table = make(andenes, PrintSpan.WEEK, time) {
                val covered = TimeSpan(time, time.plusSeconds(86400))
                val turns = listOf(TideExtreme(time.minusSeconds(60), 0.8, ExtremeKind.HIGH),
                    TideExtreme(time.plusSeconds(7200), 1.2, ExtremeKind.HIGH),
                    TideExtreme(time.plusSeconds(43200), 0.2, ExtremeKind.LOW))
                ready(covered, PredictionSeries.Tide(emptyList(), emptyList(), turns), StationCapabilities.TIDE_PREVIEW)
            }
            val day = table.days.single()
            assertNull(day.sunrise)
            assertNull(day.sunset)
            assertEquals(2, day.entries.size)
            assertTrue(day.entries.all { it.night == night })
        }
    }

    @Test fun invalidCoordinatesLeaveWaterRowsAvailableWithoutSkyAnnotations() {
        val place = station.copy(latitude = Double.NaN)
        val table = make(place) { range ->
            val turns = listOf(TideExtreme(range.start.plusSeconds(3600), 1.2, ExtremeKind.HIGH))
            ready(range, PredictionSeries.Tide(emptyList(), emptyList(), turns), StationCapabilities.HARMONIC_TIDE)
        }
        val day = table.days.single()
        assertEquals(PrintEventKind.HIGH, day.entries.single().kind)
        assertNull(day.sunrise)
        assertNull(day.sunset)
        assertFalse(day.entries.single().night)
    }

    @Test fun eclipseStartingOnLastNightAppearsOnceEvenWithPeakBeyondWeek() {
        val place = station.copy(latitude = 39.74, longitude = -104.99, timezone = "America/Denver", kind = StationKind.DerivedGate)
        val table = make(place, PrintSpan.WEEK, Instant.parse("2025-03-07T12:00:00Z")) { range ->
            ready(range, PredictionSeries.Derived(listOf(PredictionSeries.DerivedSlack(range.start, ExtremeKind.HIGH))), StationCapabilities.DERIVED_TIMING)
        }
        val row = table.days.flatMap { it.entries }.single { it.kind == PrintEventKind.LUNAR_ECLIPSE }
        val eclipse = lunarEclipses(table.range.start, table.range.end.plusSeconds(86400)).single()
        assertEquals(eclipse.u1, row.time)
        assertEquals(eclipse.peak, row.peak)
        assertTrue(row.time < table.range.end && row.peak!! > table.range.end)
        assertEquals("Point Atkinson", table.source)
        assertEquals(PrintEventKind.SLACK, table.days.first().entries.single().kind)
    }

    @Test fun printedSolarEventUsesLocalFirstContactAndCarriesPeakSeparately() {
        val place = station.copy(latitude = 32.78, longitude = -96.8, timezone = "America/Chicago")
        val table = make(place, PrintSpan.WEEK, Instant.parse("2024-04-08T13:00:00Z")) { range ->
            ready(range, PredictionSeries.Tide(emptyList(), emptyList(), emptyList()), StationCapabilities.HARMONIC_TIDE)
        }
        val expected = solarEclipses(table.range.start, table.range.end, Observer(place.latitude, place.longitude)).single()
        val row = table.days.single().entries.single()
        assertEquals(PrintEventKind.SOLAR_ECLIPSE, row.kind)
        assertEquals(expected.c1, row.time)
        assertEquals(expected.peak, row.peak)
        assertNull(row.heightM)
        assertFalse(row.night)
        assertEquals("NOAA 9449880", table.source)
    }
}
