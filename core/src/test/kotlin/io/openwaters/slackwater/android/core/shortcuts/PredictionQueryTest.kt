package io.openwaters.slackwater.android.core.shortcuts

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.settings.ReadingSettings
import io.openwaters.slackwater.android.core.station.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import kotlin.coroutines.*

class PredictionQueryTest {
    private val station = StationIdentity(StationId("test"), "Test", "CA", 49.0, -123.0,
        StationKind.Tide, timezone = "America/Vancouver")
    private val now = Instant.parse("2025-01-01T00:00:00Z")

    private fun answer(
        kind: QueryKind, date: LocalDate? = null, threshold: Double = 0.5,
        capability: StationCapabilities = if (kind == QueryKind.SLACK) StationCapabilities.FINAL_CURRENT else StationCapabilities.HARMONIC_TIDE,
        series: (TimeSpan) -> PredictionSeries,
    ): QueryAnswer {
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
                assertEquals(station.id, id)
                assertEquals(PredictionPurpose.QUERY, purpose)
                return PredictionResult.Ready(id, span, "test", capability, series(span))
            }
        }
        var completed: Result<QueryAnswer>? = null
        suspend { queryPrediction(repository, station, kind, now, date, ReadingSettings(threshold)) }
            .startCoroutine(object : Continuation<QueryAnswer> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<QueryAnswer>) { completed = result }
            })
        return requireNotNull(completed).getOrThrow()
    }

    private fun current(points: List<CurrentPoint>, vararg slacks: Instant) =
        PredictionSeries.Current(points, slacks.map { CurrentEvent(it, 0.0, CurrentEventKind.SLACK) }, null, null)

    @Test fun datedTidesReturnEveryMatchInTheStationCivilDayAcrossBothClockChanges() {
        for ((day, hours) in listOf("2025-03-09" to 23L, "2025-11-02" to 25L)) {
            val date = LocalDate.parse(day)
            val first = date.atStartOfDay(ZoneId.of("America/Vancouver")).toInstant()
            val end = first.plusSeconds(hours * 3600)
            val events = listOf(first.minusSeconds(1), first, end.minusSeconds(1), end)
                .map { TideExtreme(it, 2.0, ExtremeKind.HIGH) } + TideExtreme(first.plusSeconds(100), 0.0, ExtremeKind.LOW)
            val curve = PredictionSeries.Tide(emptyList(), emptyList(), events)
            val answer = answer(QueryKind.HIGH, date) {
                assertEquals(end, it.end)
                curve
            } as QueryAnswer.Tides
            assertEquals(listOf(first, end.minusSeconds(1)), answer.events.map { it.time })
            assertSame(curve, answer.curve)
        }
    }

    @Test fun nextTideSkipsPresentAndEarlierWrongTurn() {
        val wanted = TideExtreme(now.plusSeconds(200), -0.1, ExtremeKind.LOW)
        val curve = PredictionSeries.Tide(emptyList(), emptyList(), listOf(
            wanted.copy(time = now), wanted.copy(time = now.plusSeconds(100), kind = ExtremeKind.HIGH), wanted))
        assertEquals(listOf(wanted), (answer(QueryKind.LOW) { curve } as QueryAnswer.Tides).events)
    }

    @Test fun openWindowAfterSlackHidesOnlyTheUnobservedClose() {
        val slack = now.minusSeconds(600)
        val curve = current(listOf(CurrentPoint(now.minusSeconds(1800), 1.0),
            CurrentPoint(slack, 0.0), CurrentPoint(now.plusSeconds(600), -0.2)), slack)
        val answer = answer(QueryKind.SLACK) { curve } as QueryAnswer.Windows
        assertEquals(QueryWindow(now.minusSeconds(1200), null, now), answer.windows.single())
        assertSame(curve, answer.curve)
    }

    @Test fun datedSlackIncludesWindowFromPreviousDayWithUnobservedStart() {
        val day = LocalDate.parse("2025-11-02")
        val midnight = Instant.parse("2025-11-02T07:00:00Z")
        val earlySlack = midnight.minusSeconds(300)
        val curve = current(listOf(CurrentPoint(earlySlack, 0.0), CurrentPoint(midnight, 0.2),
            CurrentPoint(midnight.plusSeconds(1200), 0.8)), earlySlack)
        val answer = answer(QueryKind.SLACK, day) { curve } as QueryAnswer.Windows
        assertEquals(QueryWindow(null, midnight.plusSeconds(600), midnight), answer.windows.single())
    }

    @Test fun undatedSearchRequiresTheSlackInstantWithinTheHorizon() {
        val limit = now.plusSeconds(48 * 3600)
        val slack = limit.plusSeconds(300)
        val curve = current(listOf(CurrentPoint(slack.minusSeconds(1200), 1.0),
            CurrentPoint(slack, 0.0), CurrentPoint(slack.plusSeconds(1200), -1.0)), slack)
        assertEquals(QueryAnswer.None, answer(QueryKind.SLACK) { curve })
    }

    @Test fun noWindowInHorizonIsDistinctFromOffAndUnsupported() {
        val late = now.plusSeconds(49 * 3600)
        val curve = current(listOf(CurrentPoint(late.minusSeconds(600), 1.0), CurrentPoint(late, 0.0),
            CurrentPoint(late.plusSeconds(600), -1.0)), late)
        assertEquals(QueryAnswer.None, answer(QueryKind.SLACK) { curve })
        assertEquals(QueryAnswer.Off, answer(QueryKind.SLACK, threshold = 0.0) { curve })
        for (unsupported in listOf(StationCapabilities.PROVISIONAL_CURRENT, StationCapabilities.DERIVED_TIMING)) {
            assertEquals(QueryAnswer.Unavailable, answer(QueryKind.SLACK, capability = unsupported) { curve })
        }
    }
}
