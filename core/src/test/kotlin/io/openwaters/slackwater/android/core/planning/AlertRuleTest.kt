package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.android.core.station.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AlertRuleTest {
    private val old = StationId("historic")
    private val target = StationIdentity(StationId("live"), "Harbour", "", 0.0, 0.0, StationKind.Tide)
    private fun successor(shift: Double?) = StationResolution.Successor(old, StationResolution.Live(target), shift)

    @Test fun editorLeadAndExactOnceExpiry() {
        val now = Instant.parse("2026-07-01T12:00:00Z")
        val once = AlertRule.forEditor(old, AlertTrigger.Slack, now)
        val repeating = AlertRule(stationId = old, trigger = AlertTrigger.Slack)
        assertEquals(1800L, once.leadSeconds)
        assertEquals(0L, repeating.leadSeconds)
        assertFalse(once.isExpired(now))
        assertTrue(once.isExpired(now.plusNanos(1)))
        assertFalse(repeating.isExpired(Instant.MAX))
    }

    @Test fun heightUsesDatumShiftAndUnknownDatumRequiresReview() {
        val trigger = AlertTrigger.TideCrossing(2.5, false)
        val rule = AlertRule(stationId = old, trigger = trigger)
        val moved = rule.copy(stationId = target.id)
        assertEquals(moved.copy(trigger = trigger.copy(heightM = 1.75)), rule.onSuccessor(successor(-0.75)))
        assertEquals(moved.copy(enabled = false, heightNeedsReview = true), rule.onSuccessor(successor(null)))
        val extreme = rule.copy(trigger = AlertTrigger.TideExtreme(high = true))
        assertEquals(extreme.copy(stationId = target.id), extreme.onSuccessor(successor(null)))
    }
}
