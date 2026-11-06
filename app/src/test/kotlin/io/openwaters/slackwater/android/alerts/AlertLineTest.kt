package io.openwaters.slackwater.android.alerts

import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AlertLineTest {
    private val now = Instant.EPOCH
    private val station = StationIdentity(StationId("a"), "Hope Bay", "", 0.0, 0.0, StationKind.Tide)
    private val low = AlertTrigger.TideExtreme(high = false)
    private val selection = ChartAlertSelection(station, StationCapabilities.HARMONIC_TIDE, low, now.plusSeconds(90), now.plusSeconds(600))

    @Test fun aRestTapBindsToTheNextEventAndAPressBindsToTheParkedMinute() {
        val rest = selection.lineDraft(false, emptyList(), now)!!
        val pressed = selection.lineDraft(true, emptyList(), now)!!
        assertEquals(selection.nextEvent, rest.rule.once)
        assertEquals(now.plusSeconds(60), pressed.rule.once)
        assertEquals(1800, rest.rule.leadSeconds)
        assertNull(selection.copy(nextEvent = null).lineDraft(false, emptyList(), now)!!.rule.once)
        for (capability in listOf(StationCapabilities.ONLINE_CURRENT, StationCapabilities.TIDE_PREVIEW)) {
            assertNull(selection.copy(capability = capability).lineDraft(false, emptyList(), now))
        }
    }

    @Test fun setFindsThePressedRuleEvenIfOffThenSoonestAtRestThenRepeating() {
        val repeating = AlertRule(stationId = station.id, trigger = low)
        val exact = repeating.copy(id = UUID.randomUUID(), once = now.plusSeconds(60), enabled = false)
        val upcoming = repeating.copy(id = UUID.randomUUID(), once = now.plusSeconds(240))
        val rules = listOf(repeating, upcoming, exact)
        assertEquals(exact, selection.lineDraft(true, rules, now)!!.rule)
        val elsewhere = selection.copy(moment = now.plusSeconds(600))
        assertEquals(exact, elsewhere.lineDraft(false, rules, now)!!.rule)
        assertEquals(repeating, elsewhere.lineDraft(true, rules, now)!!.rule)
    }

    @Test fun sameEventDifferentLeadsGetsOneMarkerAtItsMinuteInOneTile() {
        val event = now.plusSeconds(3600)
        val first = AlertOccurrence(UUID.randomUUID(), event, event.minusSeconds(1800))
        val second = AlertOccurrence(UUID.randomUUID(), event.plusSeconds(3), event)
        val events = listOf(first, second)
        assertTrue(markerMinutes(events, TimeSpan(now, event)).isEmpty())
        assertEquals(listOf(event), markerMinutes(events, TimeSpan(event, event.plusSeconds(1800))))
    }
}
