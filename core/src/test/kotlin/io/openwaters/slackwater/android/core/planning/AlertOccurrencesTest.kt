package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationId
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.*

class AlertOccurrencesTest {
    private val id = StationId("noaa:fixture")
    private val zone = ZoneId.of("America/Vancouver")
    private fun t(minutes: Long) = Instant.EPOCH.plusSeconds(minutes * 60)
    private val hour = TimeSpan(t(0), t(60))
    private var requested: TimeSpan? = null

    private fun resolve(
        trigger: AlertTrigger, series: PredictionSeries, span: TimeSpan = hour,
        threshold: Double = 0.5, once: Instant? = null, lead: Long = 0, sun: SunWindow? = null,
        capability: StationCapabilities = StationCapabilities.HARMONIC_CURRENT,
    ): List<AlertOccurrence> {
        val repository = object : PredictionRepository {
            override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
                assertEquals(PredictionPurpose.ALERT, purpose)
                requested = span
                return PredictionResult.Ready(id, span, "fixture", capability, series)
            }
        }
        val rule = AlertRule(stationId = id, trigger = trigger, once = once, leadSeconds = lead, sun = sun)
        // This fixture repository returns synchronously.
        val block = suspend { OccurrenceResolver.resolve(rule, repository, span, zone, 49.0, -123.0, threshold) }
        var answer: Result<List<AlertOccurrence>>? = null
        block.startCoroutine(object : Continuation<List<AlertOccurrence>> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<List<AlertOccurrence>>) { answer = result }
        })
        return requireNotNull(answer).getOrThrow()
    }

    private fun current(vararg speeds: Double, slackMinutes: List<Long>) = PredictionSeries.Current(
        speeds.mapIndexed { index, speed -> CurrentPoint(t(index * 10L), speed) },
        slackMinutes.map { CurrentEvent(t(it), 0.0, CurrentEventKind.SLACK) }, null, null,
    )

    @Test fun sampledOverReversalsKeepTheirObservedWindowEvenBelowOneSampleStep() {
        val series = current(-2.0, 2.0, slackMinutes = listOf(5))
        val event = resolve(AlertTrigger.SlackWindowOpens, series).single()
        assertEquals(t(3), event.event)
        assertEquals(t(6), event.end)
        assertFalse(event.noWindow)
        val off = resolve(AlertTrigger.SlackWindowOpens, series, threshold = 0.0).single()
        assertEquals(t(5), off.event)
        assertNull(off.end)
        assertTrue(off.noWindow)
        val outside = current(-2.0, 2.0, slackMinutes = listOf(12))
        val uncovered = resolve(AlertTrigger.SlackWindowOpens, outside).single()
        assertTrue(uncovered.noWindow)
        assertEquals(t(12), uncovered.event)
    }

    @Test fun clippedEdgesNeverBecomeObservedOpeningsOrClosings() {
        val clippedStart = current(-0.2, 0.2, 2.0, slackMinutes = listOf(5))
        assertTrue(resolve(AlertTrigger.SlackWindowOpens, clippedStart).isEmpty())
        val clippedEnd = current(-1.0, -0.2, 0.2, slackMinutes = listOf(15))
        val opening = resolve(AlertTrigger.SlackWindowOpens, clippedEnd).single()
        assertEquals(t(6), opening.event)
        assertNull(opening.end)
        assertFalse(opening.noWindow)
    }

    @Test fun currentPeaksAndDerivedSlacksRequireMatchingSeries() {
        val series = current(-1.0, 2.0, slackMinutes = emptyList()).copy(events = listOf(
            CurrentEvent(t(7), -1.0, CurrentEventKind.MAX_EBB),
            CurrentEvent(t(48), 2.0, CurrentEventKind.MAX_FLOOD),
        ))
        assertEquals(t(7), resolve(AlertTrigger.CurrentPeak(false), series).single().event)
        assertEquals(t(48), resolve(AlertTrigger.CurrentPeak(true), series).single().event)
        assertTrue(resolve(AlertTrigger.Slack, series).isEmpty())
        assertTrue(resolve(AlertTrigger.CurrentPeak(true), series, capability = StationCapabilities.ONLINE_CURRENT).isEmpty())
        val derived = PredictionSeries.Derived(listOf(PredictionSeries.DerivedSlack(t(10), ExtremeKind.HIGH)))
        assertEquals(t(10), resolve(AlertTrigger.Slack, derived).single().event)
        assertTrue(resolve(AlertTrigger.SlackWindowOpens, derived).isEmpty())
    }

    @Test fun directionAndMetresDetermineCrossingsAndExactSamplesCountOnce() {
        val series = PredictionSeries.Tide(listOf(0.0, 1.0, 1.0, 2.0, 0.0).mapIndexed { i, height ->
            TidePoint(t(10L * i), height)
        }, emptyList(), emptyList())
        val up = resolve(AlertTrigger.TideCrossing(1.0, true), series).single()
        assertEquals(t(10), up.event)
        assertEquals(1.0, up.heightM!!, 0.0)
        assertEquals(t(35), resolve(AlertTrigger.TideCrossing(1.0, false), series).single().event)
        assertTrue(resolve(AlertTrigger.TideCrossing(1.0, true), series, TimeSpan(t(11), t(39))).isEmpty())
        assertEquals(TimeSpan(t(10), t(40)), requested)
    }

    @Test fun dailyRanksCompareTheEntireLocalDayIncludingTheRepeatedHour() {
        val midnight = Instant.parse("2025-11-02T07:00:00Z")
        val next = Instant.parse("2025-11-03T08:00:00Z")
        fun hour(index: Long) = midnight.plusSeconds(index * 3600)
        val series = PredictionSeries.Tide(emptyList(), emptyList(), listOf(
            TideExtreme(hour(1), 2.0, ExtremeKind.HIGH), TideExtreme(hour(2), -1.0, ExtremeKind.LOW),
            TideExtreme(hour(13), 4.0, ExtremeKind.HIGH), TideExtreme(hour(24), -2.0, ExtremeKind.LOW),
        ))
        val morning = TimeSpan(hour(1), hour(3))
        assertTrue(resolve(AlertTrigger.TideExtreme(true, true), series, morning).isEmpty())
        assertEquals(TimeSpan(midnight, next), requested)
        assertEquals(hour(1), resolve(AlertTrigger.TideExtreme(true), series, morning).single().event)
        assertEquals(hour(2), resolve(AlertTrigger.TideExtreme(false), series, morning).single().event)
        val day = TimeSpan(midnight, next.minusSeconds(1))
        assertEquals(hour(13), resolve(AlertTrigger.TideExtreme(true, true), series, day).single().event)
        assertEquals(hour(24), resolve(AlertTrigger.TideExtreme(false, true), series, day).single().event)
    }

    @Test fun onceChoosesNearestMinuteIncludingSixHourBoundary() {
        fun slack(time: Instant) = PredictionSeries.DerivedSlack(time, ExtremeKind.LOW)
        val series = PredictionSeries.Derived(listOf(slack(t(5).plusSeconds(59)), slack(t(45).plusSeconds(4))))
        val result = resolve(AlertTrigger.Slack, series, once = t(43), lead = 120).single()
        assertEquals(t(45), result.event)
        assertEquals(t(43), result.fire)
        assertEquals("${result.ruleId}.${t(45).epochSecond}", result.key)
        assertEquals(t(45), resolve(AlertTrigger.Slack, series, once = t(405)).single().event)
        assertTrue(resolve(AlertTrigger.Slack, series, once = t(406)).isEmpty())
    }

    @Test fun aOnceRuleCannotMoveToAnotherOpeningWhenTheHorizonAdvances() {
        val points = listOf(680L to -1.0, 700L to 0.0, 720L to 0.0, 740L to 1.0,
            1050L to 1.0, 1070L to 0.0, 1090L to 0.0, 1110L to -1.0)
        val series = PredictionSeries.Current(points.map { (minute, speed) -> CurrentPoint(t(minute), speed) },
            listOf(720L, 1090L).map { CurrentEvent(t(it), 0.0, CurrentEventKind.SLACK) }, null, null)
        val trigger = AlertTrigger.SlackWindowOpens
        val once = t(720)
        assertEquals(t(690), resolve(trigger, series, TimeSpan(t(600), t(1200)), once = once).single().event)
        assertTrue(resolve(trigger, series, TimeSpan(t(705), t(1200)), once = once).isEmpty())
    }

    @Test fun sunFiltersApplyToTheEventEvenWhenItsReminderFiresAtNight() {
        val start = Instant.parse("2026-06-21T07:00:00Z")
        val span = TimeSpan(start, start.plusSeconds(86400))
        val slacks = (0..1440 step 5).map { minute ->
            PredictionSeries.DerivedSlack(start.plusSeconds(minute * 60L), ExtremeKind.LOW)
        }
        val series = PredictionSeries.Derived(slacks)
        val daylight = resolve(AlertTrigger.Slack, series, span, lead = 43200, sun = SunWindow.DAYLIGHT)
        assertTrue(daylight.any { it.event == start.plusSeconds(43200) && it.fire == start })
        assertFalse(daylight.any { it.event == start })
        val golden = resolve(AlertTrigger.Slack, series, span, sun = SunWindow.GOLDEN_HOUR)
        val blue = resolve(AlertTrigger.Slack, series, span, sun = SunWindow.BLUE_HOUR)
        assertTrue(golden.isNotEmpty() && blue.isNotEmpty())
        assertTrue(daylight.map { it.event }.containsAll(golden.map { it.event }))
        assertTrue(blue.none { b -> daylight.any { it.event == b.event } })
    }

    @Test fun eclipseOccurrenceUsesFirstUmbralContact() {
        val span = TimeSpan(Instant.parse("2026-03-03T00:00:00Z"), Instant.parse("2026-03-04T00:00:00Z"))
        val event = resolve(AlertTrigger.Eclipse, PredictionSeries.Derived(emptyList()), span).single()
        val first = io.openwaters.almanac.lunarEclipses(span.start, span.end).single().u1!!
        assertEquals(first.truncatedTo(java.time.temporal.ChronoUnit.MINUTES), event.event)
    }
}
