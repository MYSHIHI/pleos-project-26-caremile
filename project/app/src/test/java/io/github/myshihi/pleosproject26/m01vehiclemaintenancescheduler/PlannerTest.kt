package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlannerTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val car = Vehicle("v", "테스트", RuleBook.MODEL, RuleBook.YEAR, "하이브리드", LocalDate.of(2023, 3, 15), sample = true, profileConfirmed = true)
    private val prefs = Preferences()
    private val noRate = Rate(null, "없음")
    private fun rec(item: Item, action: Action, date: LocalDate, km: Int, fromNew: Boolean = false) =
        Record("${item.id}-$action-$date-$km", "v", item.id, action, date, km, Source.USER, fromNew)
    private fun rule(item: Item, action: Action) = RuleBook.rules.first { it.item == item && it.action == action }
    private fun plan(item: Item, action: Action, records: List<Record>, km: Int?, rate: Rate = noRate, vehicle: Vehicle = car) =
        Planner.planItem(rule(item, action), vehicle, records, km, rate, today, prefs)

    @Test fun distanceReachedFirstMakesItemDue() {
        val p = plan(RuleBook.engineOil, Action.REPLACE, listOf(rec(RuleBook.engineOil, Action.REPLACE, today.minusMonths(3), 30_000)), 40_100)
        assertEquals(Status.OVERDUE, p.status)
        assertEquals(40_000, p.dueKm)
        assertEquals(-100, p.remainingKm)
    }

    @Test fun periodReachedFirstMakesItemDueEvenWithDistanceLeft() {
        val p = plan(RuleBook.engineOil, Action.REPLACE, listOf(rec(RuleBook.engineOil, Action.REPLACE, today.minusMonths(12), 38_000)), 39_000)
        assertEquals(Status.OVERDUE, p.status)
        assertEquals(today, p.dueDate)
        assertEquals(9_000, p.remainingKm)
    }

    @Test fun projectionDecidesWhichCriterionComesFirst() {
        val records = listOf(rec(RuleBook.engineOil, Action.REPLACE, today.minusMonths(6), 30_000))
        val fast = plan(RuleBook.engineOil, Action.REPLACE, records, 38_000, Rate(100.0, "")) // 2,000 km left -> 20 days
        assertEquals("거리 기준", fast.firstBy)
        assertEquals(today.plusDays(20), fast.deadline)
        val slow = plan(RuleBook.engineOil, Action.REPLACE, records, 38_000, Rate(5.0, "")) // 400 days vs 6 months
        assertEquals("기간 기준", slow.firstBy)
        assertEquals(today.minusMonths(6).plusMonths(12), slow.deadline)
    }

    @Test fun withoutEnoughReadingsNoDateIsInvented() {
        val p = plan(RuleBook.cabinFilter, Action.REPLACE, listOf(rec(RuleBook.cabinFilter, Action.REPLACE, today.minusMonths(6), 30_000)), 40_000)
        assertNull(p.projectedKmDate)
        assertNull(p.deadline)
        assertEquals("거리 기준", p.firstBy) // distance is the only criterion for this item
        assertEquals(Status.OK, p.status)
        val both = plan(RuleBook.engineOil, Action.REPLACE, listOf(rec(RuleBook.engineOil, Action.REPLACE, today.minusMonths(6), 30_000)), 35_000)
        assertNull(both.firstBy) // distance and period both apply, but without a rate we cannot say which comes first
        assertEquals(today.minusMonths(6).plusMonths(12), both.deadline)
    }

    @Test fun rateNeedsTwoWeeksOfIncreasingReadings() {
        val short = listOf(Reading(100.0, 1, today.minusDays(5), Source.SAMPLE), Reading(300.0, 2, today, Source.SAMPLE))
        assertNull(Planner.rate(short, today).kmPerDay)
        val ok = listOf(Reading(100.0, 1, today.minusDays(20), Source.SAMPLE), Reading(900.0, 2, today, Source.SAMPLE))
        assertEquals(40.0, Planner.rate(ok, today).kmPerDay!!, 0.001)
    }

    @Test fun missingHistoryStaysNeedsCheckAndIsNotPlanned() {
        val p = plan(RuleBook.brakeFluid, Action.REPLACE, emptyList(), 40_000)
        assertEquals(Status.NEEDS_HISTORY, p.status)
        assertFalse(Planner.isTarget(p, today, prefs))
    }

    @Test fun inspectionDoesNotResetReplacementInterval() {
        val records = listOf(
            rec(RuleBook.airCleaner, Action.REPLACE, today.minusYears(3), 0),
            rec(RuleBook.airCleaner, Action.INSPECT, today.minusMonths(1), 39_000),
        )
        assertEquals(40_000, plan(RuleBook.airCleaner, Action.REPLACE, records, 39_500).dueKm)
        assertEquals(49_000, plan(RuleBook.airCleaner, Action.INSPECT, records, 39_500).dueKm)
        // A replacement also counts as the item having been checked.
        val replacedLater = records + rec(RuleBook.airCleaner, Action.REPLACE, today, 39_600)
        assertEquals(49_600, plan(RuleBook.airCleaner, Action.INSPECT, replacedLater, 39_600).dueKm)
    }

    @Test fun firstIntervalFromNewThenRepeatInterval() {
        val fromNew = plan(RuleBook.coolant, Action.REPLACE, listOf(rec(RuleBook.coolant, Action.REPLACE, LocalDate.of(2023, 3, 15), 0, fromNew = true)), 40_000)
        assertEquals(200_000, fromNew.dueKm)
        assertEquals(LocalDate.of(2033, 3, 15), fromNew.dueDate)
        val after = plan(RuleBook.coolant, Action.REPLACE, listOf(rec(RuleBook.coolant, Action.REPLACE, LocalDate.of(2026, 1, 10), 35_000)), 40_000)
        assertEquals(75_000, after.dueKm)
        assertEquals(LocalDate.of(2028, 1, 10), after.dueDate)
    }

    @Test fun monthEndAndLeapDayClamp() {
        val leap = plan(RuleBook.aircon, Action.INSPECT, listOf(rec(RuleBook.aircon, Action.INSPECT, LocalDate.of(2024, 2, 29), 1_000)), 2_000)
        assertEquals(LocalDate.of(2025, 2, 28), leap.dueDate)
        val hsg = plan(RuleBook.engineOil, Action.REPLACE, listOf(rec(RuleBook.engineOil, Action.REPLACE, LocalDate.of(2026, 8, 31), 1_000)), 2_000,
            vehicle = car.copy(severe = setOf(1)))
        assertEquals(LocalDate.of(2027, 2, 28), hsg.dueDate)
    }

    @Test fun severeConditionUsesManualSevereInterval() {
        val records = listOf(rec(RuleBook.engineOil, Action.REPLACE, today.minusMonths(7), 30_000))
        assertEquals(Status.OK, plan(RuleBook.engineOil, Action.REPLACE, records, 33_000).status)
        val severe = plan(RuleBook.engineOil, Action.REPLACE, records, 33_000, vehicle = car.copy(severe = setOf(1)))
        assertTrue(severe.severeApplied)
        assertEquals(35_000, severe.dueKm)
        assertEquals(Status.OVERDUE, severe.status) // 6 months passed
    }

    @Test fun severeWithoutNumberAddsNoteButKeepsNormalCycle() {
        val records = listOf(rec(RuleBook.cabinFilter, Action.REPLACE, today.minusMonths(2), 30_000))
        val p = plan(RuleBook.cabinFilter, Action.REPLACE, records, 31_000, vehicle = car.copy(severe = setOf(2)))
        assertFalse(p.severeApplied)
        assertNotNull(p.severeNote)
        assertEquals(45_000, p.dueKm)
    }

    @Test fun transmissionFluidOnlyScheduledUnderMatchingSevereCondition() {
        assertEquals(Status.NOT_SCHEDULED, plan(RuleBook.atf, Action.REPLACE, emptyList(), 40_000).status)
        assertEquals(Status.NEEDS_HISTORY, plan(RuleBook.atf, Action.REPLACE, emptyList(), 40_000, vehicle = car.copy(severe = setOf(1))).status)
        assertEquals(Status.NOT_SCHEDULED, plan(RuleBook.atf, Action.REPLACE, emptyList(), 40_000, vehicle = car.copy(severe = setOf(2))).status)
    }

    @Test fun unknownOdometerIsNotTreatedAsFine() {
        val p = plan(RuleBook.cabinFilter, Action.REPLACE, listOf(rec(RuleBook.cabinFilter, Action.REPLACE, today.minusMonths(2), 30_000)), null)
        assertEquals(Status.NEEDS_ODOMETER, p.status)
    }

    @Test fun otherModelsAndUnconfirmedProfilesGetNoSchedule() {
        assertTrue(Planner.plan(car.copy(model = "기타"), emptyList(), 1_000, noRate, today, prefs).isEmpty())
        assertTrue(Planner.plan(car.copy(profileConfirmed = false), emptyList(), 1_000, noRate, today, prefs).isEmpty())
        assertEquals(RuleBook.rules.size, Planner.plan(car, emptyList(), 1_000, noRate, today, prefs).size)
    }
}
