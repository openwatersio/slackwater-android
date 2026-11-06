package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.android.core.station.StationId
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DeliveryPlanTest {
    private val now = Instant.parse("2026-11-04T18:00:00Z")
    private val rule = AlertRule(stationId = StationId("test"), trigger = AlertTrigger.Slack)
    private val purchased = ReminderEntitlement.Purchased

    private fun occurrence(minutes: Long, source: AlertRule = rule): AlertOccurrence {
        val event = now.plusSeconds(minutes * 60)
        return AlertOccurrence(ruleId = source.id, event = event, fire = event.minusSeconds(source.leadSeconds))
    }

    @Test fun budgetAndBackgroundRefillReportOnlyActualCoverage() {
        val events = (1L..67L).map { occurrence(it) }
        val plan = deliveryPlan(listOf(rule), events.reversed(), emptyList(), now, purchased)
        assertEquals(events.take(64), plan.notifications)
        assertEquals(events.drop(64), plan.overflow)
        assertEquals(events[63].fire, plan.scheduledThrough[rule.id])
        val refilled = plan.refill(setOf(events[1].key), events[1].fire, purchased)
        assertEquals(events.subList(2, 66), refilled.notifications)
        assertEquals(events.drop(66), refilled.overflow)
        assertEquals(events[65].fire, refilled.scheduledThrough[rule.id])
        assertEquals(plan.horizon, refilled.horizon)
        assertEquals(refilled, refilled.refill(setOf(events[1].key), events[1].fire, purchased))
    }

    @Test fun staleOverflowCannotFireAfterTheHorizonAndForegroundCanExtendIt() {
        val edge = 14L * 24 * 60
        val events = listOf(occurrence(edge), occurrence(edge + 1))
        val plan = deliveryPlan(listOf(rule), events, emptyList(), now, purchased)
        assertEquals(listOf(events.first()), plan.notifications)
        val later = now.plusSeconds(60)
        assertEquals(plan, plan.refill(emptySet(), later, purchased))
        assertTrue(plan.refill(emptySet(), events.last().fire, purchased).notifications.isEmpty())
        assertEquals(events, deliveryPlan(listOf(rule), events, emptyList(), later, purchased).notifications)
    }

    @Test fun trialEndsExactlyAtExpiryInBothQueuesAndPurchaseRebuildsPastIt() {
        val events = (1L..68L).map { occurrence(it) }
        for (boundary in listOf(63, 64, 65)) {
            val trial = ReminderEntitlement.FreeDay(events[boundary].fire)
            val active = deliveryPlan(listOf(rule), events, emptyList(), now, trial)
            assertEquals(events.take(boundary), active.notifications + active.overflow)
            val bought = deliveryPlan(listOf(rule), events, emptyList(), now, purchased)
            assertEquals(events, bought.notifications + bought.overflow)
            assertEquals(active, bought.refill(emptySet(), now, trial))
            assertTrue(active.refill(emptySet(), trial.ends, trial).notifications.isEmpty())
        }
    }

    @Test fun freeTierRetainsOnlyFutureSubscriptionEventsForNinetyDays() {
        val last = 90L * 24 * 60
        val calendar = listOf(occurrence(last + 1), occurrence(last), occurrence(1), occurrence(0))
        val plan = deliveryPlan(listOf(rule), listOf(occurrence(5)), calendar, now, ReminderEntitlement.Free)
        assertEquals(listOf(occurrence(1), occurrence(last)), plan.calendar)
        assertTrue(plan.notifications.isEmpty())
        assertTrue(plan.overflow.isEmpty())
    }

    @Test fun onceWinsIdenticalReminderAndOtherLeadsRemain() {
        val once = rule.copy(id = UUID.randomUUID(), once = now.plus(Duration.ofHours(1)))
        val earlier = rule.copy(id = UUID.randomUUID(), leadSeconds = 900)
        val disabled = rule.copy(id = UUID.randomUUID(), enabled = false)
        val rules = listOf(rule, once, earlier, disabled)
        val events = rules.map { occurrence(60, it) }
        val plan = deliveryPlan(rules, events, emptyList(), now, purchased)
        assertEquals(listOf(events[2], events[1]), plan.notifications)
    }

    @Test fun onlySavingTheMatchingRuleCanBringAPastLeadForward() {
        val early = rule.copy(leadSeconds = 1800)
        val events = listOf(occurrence(-1, early), occurrence(0, early), occurrence(10, early), occurrence(30, early))
        fun plan(saved: UUID?) = deliveryPlan(listOf(early), events, emptyList(), now, purchased, setOfNotNull(saved))
        assertEquals(listOf(events.last()), plan(null).notifications)
        assertEquals(plan(null), plan(UUID.randomUUID()))
        assertEquals(listOf(events[2].copy(fire = now, originalFire = events[2].fire), events.last()), plan(early.id).notifications)
    }

    @Test fun pendingImmediateIsRevalidatedAgainstFreshRulesOccurrencesAndEntitlement() {
        val early = rule.copy(leadSeconds = 1800)
        val original = occurrence(10, early)
        val first = deliveryPlan(listOf(early), listOf(original), emptyList(), now, purchased, setOf(early.id))
        val later = now.plusSeconds(1)
        fun followUp(
            rules: List<AlertRule> = listOf(early),
            occurrences: List<AlertOccurrence> = listOf(original),
            access: ReminderEntitlement = purchased,
            at: Instant = later,
        ) = deliveryPlan(rules, occurrences, emptyList(), at, access, previous = first).notifications
        assertEquals(first.notifications, followUp())
        assertTrue(followUp(rules = emptyList()).isEmpty())
        assertTrue(followUp(rules = listOf(early.copy(enabled = false))).isEmpty())
        assertTrue(followUp(occurrences = emptyList()).isEmpty())
        val edited = early.copy(leadSeconds = 900)
        assertTrue(followUp(rules = listOf(edited), occurrences = listOf(occurrence(10, edited))).isEmpty())
        assertTrue(followUp(access = ReminderEntitlement.FreeDay(later)).isEmpty())
        assertTrue(followUp(at = original.event).isEmpty())
    }
}
