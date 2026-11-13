package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class VisitAndBookingTest {
    private val today = LocalDate.of(2026, 11, 11) // Wednesday
    private val now = today.atTime(9, 0)
    private val allBlocks = Preferences(blocks = TimeBlock.entries.toSet(), preferredShops = setOf("shop_a"))

    private fun seeded(): CareState = CareState().also { val now = today.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        SampleData.seed(it, today, now)
        // These tests cover planning under normal conditions; driving-history tailoring is covered in DrivingTest.
        SampleData.rebuildDriving(it, SampleData.A, DrivePattern.BALANCED, today, now, 41_600.0)
    }

    @Test fun sampleShowsDueWorkAndBundlesIntoOneVisit() {
        val s = seeded()
        val plans = s.plans(today)
        assertEquals(Status.OVERDUE, plans.first { it.rule.item == RuleBook.aircon }.status)
        assertEquals(Status.SOON, plans.first { it.rule.item == RuleBook.engineOil }.status)
        assertEquals(Status.NEEDS_HISTORY, plans.first { it.rule.item == RuleBook.brakeFluid }.status)
        val vp = s.visitPlan(plans, today, now)
        assertTrue(vp.visits.isNotEmpty())
        val first = vp.visits.first()
        assertEquals("shop_a", first.shop.id)
        assertEquals(vp.targets.size, vp.visits.sumOf { it.items.size } + vp.unplaced.size)
        // Every planned visit is within each item's latest acceptable day and not earlier than allowed.
        vp.visits.forEach { v -> v.items.forEach { p ->
            val day = v.slot.at.toLocalDate()
            assertFalse(day.isAfter(Planner.latest(p, today, s.prefsOf())))
            assertFalse(day.isBefore(Planner.earliest(p, today, s.prefsOf())))
        } }
    }

    @Test fun overdueWorkIsNotPushedToLateSlots() {
        val s = seeded()
        val plans = s.plans(today)
        val aircon = plans.first { it.rule.item == RuleBook.aircon }
        val shop = Shop("x", "late", "", mapOf(aircon.rule.id to Price(1, true)), listOf(20 to 10))
        val vp = Planner.visits(listOf(aircon), emptySet(), listOf(shop), allBlocks, today, now)
        assertTrue(vp.visits.isEmpty())
        assertEquals(1, vp.unplaced.size)
    }

    @Test fun workIsNotPulledForwardBeyondAdvanceLimit() {
        val s = seeded()
        val p = s.plans(today).first { it.rule.item == RuleBook.engineOil }.copy(status = Status.OK, deadline = today.plusDays(40))
        val shop = Shop("x", "early", "", mapOf(p.rule.id to Price(1, true)), listOf(1 to 10, 15 to 10))
        val c = Planner.candidates(listOf(p), listOf(shop), allBlocks, today, now)
        assertEquals(listOf(today.plusDays(15)), c.map { it.slot.at.toLocalDate() })
    }

    @Test fun userTimeBlocksAndBudgetAreRespected() {
        val s = seeded()
        val plans = s.plans(today)
        val evenings = Preferences(blocks = setOf(TimeBlock.WEEKDAY_EVENING), budget = 50_000)
        Planner.visits(plans, emptySet(), SampleData.shops, evenings, today, now).visits.forEach {
            assertEquals(TimeBlock.WEEKDAY_EVENING, blockOf(it.slot.at))
        }
        val cands = Planner.candidates(plans.filter { Planner.isTarget(it, today, evenings) }, SampleData.shops, evenings, today, now)
        assertTrue(cands.any { !it.withinBudget })
    }

    @Test fun duplicateRequestDoesNotCreateSecondBooking() {
        val s = seeded()
        val visit = s.visitPlan(s.plans(today), today, now).visits.first()
        assertTrue(s.request(visit, false, 0) is RequestResult.Created)
        assertTrue(s.request(visit, false, 1) is RequestResult.Duplicate)
        val other = s.visitPlan(s.plans(today), today, now).visits.firstOrNull()
        if (other != null) assertTrue(s.request(other, false, 2) is RequestResult.Blocked)
        assertEquals(1, s.bookings.size)
    }

    @Test fun requestAndWaitAreNeverShownAsConfirmed() {
        val s = seeded()
        s.scenario = Scenario.WAIT
        val visit = s.visitPlan(s.plans(today), today, now).visits.first()
        s.request(visit, false, 0)
        s.tick(1_000); assertEquals(BookingState.REQUESTED, s.bookings[0].state)
        s.tick(BookingFlow.STEP_MS); assertEquals(BookingState.PENDING, s.bookings[0].state)
        s.tick(BookingFlow.STEP_MS * 2); assertEquals(BookingState.PENDING, s.bookings[0].state)
        s.tick(BookingFlow.STEP_MS * 3); assertEquals(BookingState.CONFIRMED, s.bookings[0].state)
    }

    @Test fun lostResponseNeedsLookupNotNewRequest() {
        val s = seeded()
        s.scenario = Scenario.LOST
        val visit = s.visitPlan(s.plans(today), today, now).visits.first()
        s.request(visit, false, 0)
        s.tick(BookingFlow.STEP_MS)
        assertEquals(BookingState.UNKNOWN, s.bookings[0].state)
        assertTrue(s.request(visit, false, BookingFlow.STEP_MS + 1) is RequestResult.Duplicate)
        s.replaceBooking(BookingFlow.lookup(s.bookings[0], 10_000))
        assertEquals(BookingState.PENDING, s.bookings[0].state)
        assertEquals(1, s.bookings.size)
    }

    @Test fun rejectedAutoRequestIsNotRetriedAutomatically() {
        val s = seeded()
        s.scenario = Scenario.REJECT
        val visit = s.visitPlan(s.plans(today), today, now).visits.first()
        s.request(visit, auto = true, now = 0)
        s.tick(BookingFlow.STEP_MS)
        assertEquals(BookingState.REJECTED, s.bookings[0].state)
        assertTrue(visit.requestKey(s.vehicleId) in s.autoTried)
    }

    @Test fun autoBookingOnlyWithinPermission() {
        val s = seeded()
        val visit = s.visitPlan(s.plans(today), today, now).visits.first()
        val off = s.prefsOf()
        assertTrue(BookingFlow.autoBlockers(visit, off, true, false).isNotEmpty())
        val on = off.copy(auto = AutoBooking(true, 1_000_000), budget = 1_000_000)
        assertTrue(visit.preferred && visit.unpriced.isEmpty())
        assertEquals(emptyList<String>(), BookingFlow.autoBlockers(visit, on, true, false))
        assertTrue(BookingFlow.autoBlockers(visit, on, true, true).isNotEmpty())
        assertTrue(BookingFlow.autoBlockers(visit, on, false, false).any { "주행거리" in it })
        assertTrue(BookingFlow.autoBlockers(visit, on.copy(auto = AutoBooking(true, 1_000)), true, false).any { "한도" in it })
    }

    @Test fun bookingAloneCreatesNoRecordAndResultOnlyAddsPerformedWork() {
        val s = seeded()
        s.scenario = Scenario.CONFIRM
        val visit = s.visitPlan(s.plans(today), today, now).visits.first()
        s.request(visit, false, 0)
        val before = s.records.size
        s.tick(BookingFlow.STEP_MS)
        assertEquals(BookingState.CONFIRMED, s.bookings[0].state)
        assertEquals(before, s.records.size)
        val done = visit.ruleIds.take(1)
        assertEquals(1, s.applyResult(s.bookings[0].id, done, today, 41_700, 10_000))
        assertEquals(before + 1, s.records.size)
        assertEquals(BookingState.COMPLETED, s.bookings[0].state)
        assertEquals(0, s.applyResult(s.bookings[0].id, done, today, 41_700, 11_000))
        // The performed item restarts its cycle; the skipped ones stay due.
        val plans = s.plans(today)
        val performed = plans.first { it.rule.id == done[0] }
        assertEquals(Status.OK, performed.status)
        visit.ruleIds.drop(1).forEach { id -> assertTrue(Planner.isTarget(plans.first { it.rule.id == id }, today, s.prefsOf())) }
    }

    @Test fun changeKeepsBookingAndConfirmsNewTime() {
        val s = seeded()
        s.scenario = Scenario.CONFIRM
        val visit = s.visitPlan(s.plans(today), today, now).visits.first()
        s.request(visit, false, 0); s.tick(BookingFlow.STEP_MS)
        val to = LocalDateTime.of(2026, 11, 14, 10, 0)
        s.replaceBooking(BookingFlow.change(s.bookings[0], to, 10_000))
        assertEquals(BookingState.CHANGE_REQUESTED, s.bookings[0].state)
        s.tick(10_000 + BookingFlow.STEP_MS)
        assertEquals(BookingState.CONFIRMED, s.bookings[0].state)
        assertEquals(to, s.bookings[0].at)
    }
}
