package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DrivingTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = today.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()

    private fun stateWith(p: DrivePattern) = CareState().also {
        SampleData.seed(it, today, now)
        SampleData.rebuildDriving(it, SampleData.A, p, today, now, 41_600.0)
    }

    @Test fun eachSamplePatternIsDetectedAsItsManualConditions() {
        assertEquals(emptySet<Int>(), stateWith(DrivePattern.BALANCED).profile(today).detected)
        assertEquals(setOf(1), stateWith(DrivePattern.COMMUTE).profile(today).detected)
        assertTrue(stateWith(DrivePattern.CITY).profile(today).detected.containsAll(setOf(3, 4, 9)))
        assertEquals(setOf(8), stateWith(DrivePattern.HIGHWAY).profile(today).detected)
    }

    @Test fun drivingHistoryTailorsIntervalsWithManualSevereValuesOnly() {
        val balanced = stateWith(DrivePattern.BALANCED)
        val commute = stateWith(DrivePattern.COMMUTE)
        val oilNormal = balanced.plans(today).first { it.rule.item == RuleBook.engineOil }
        val oilTailored = commute.plans(today).first { it.rule.item == RuleBook.engineOil }
        assertEquals(Interval(10_000, 12), oilNormal.interval)
        assertEquals(Interval(5_000, 6), oilTailored.interval)
        assertTrue(oilTailored.severeApplied)
        // Highway driving puts the transmission fluid on the manual's severe schedule.
        assertEquals(Status.NOT_SCHEDULED, balanced.plans(today).first { it.rule.item == RuleBook.atf }.status)
        assertNotEquals(Status.NOT_SCHEDULED, stateWith(DrivePattern.HIGHWAY).plans(today).first { it.rule.item == RuleBook.atf }.status)
    }

    @Test fun userCanExcludeADetectedCondition() {
        val s = stateWith(DrivePattern.COMMUTE)
        assertEquals(setOf(1), s.effectiveSevere(today))
        s.updateVehicle(s.vehicle.copy(excluded = setOf(1)))
        assertEquals(emptySet<Int>(), s.effectiveSevere(today))
        assertEquals(Interval(10_000, 12), s.plans(today).first { it.rule.item == RuleBook.engineOil }.interval)
    }

    @Test fun tooFewTripsGiveNoDataInsteadOfAGuess() {
        val s = stateWith(DrivePattern.COMMUTE)
        s.trips.removeAll { true }
        val p = s.profile(today)
        assertFalse(p.enough)
        assertTrue(p.findings.all { it.state == FindingState.NO_DATA })
        assertTrue(p.quietBlocks.isEmpty())
    }

    @Test fun missingSignalsLeaveOnlyThoseConditionsUndecided() {
        val s = stateWith(DrivePattern.COMMUTE)
        s.trips.replaceAll { it.copy(idleShare = null, highSpeedShare = null, hardEvents = null, brakePresses = null, outsideC = null) }
        val p = s.profile(today)
        assertEquals(FindingState.DETECTED, p.findings.first { it.condition == 1 }.state)
        listOf(3, 8, 9, 10).forEach { c -> assertEquals(FindingState.NO_DATA, p.findings.first { it.condition == c }.state) }
    }

    @Test fun parkedTimesBecomePreferredVisitSlots() {
        val s = stateWith(DrivePattern.COMMUTE)
        val quiet = s.profile(today).quietBlocks
        assertTrue(TimeBlock.WEEKEND_MORNING in quiet)
        assertFalse(TimeBlock.WEEKDAY_EVENING in quiet)
        val vp = s.visitPlan(s.plans(today), today, today.atTime(9, 0))
        assertTrue(vp.visits.isNotEmpty())
    }

    @Test fun sampleReadingsMatchTripsAndEndAtCurrentKm() {
        val s = stateWith(DrivePattern.HIGHWAY)
        assertEquals(41_600, s.currentKm())
        val r = s.readingsOf().sortedBy { it.atMillis }
        assertTrue(r.zipWithNext().all { (a, b) -> b.km >= a.km })
        assertEquals(60, r.size)
    }

    @Test fun tripBuilderUsesOdometerAndCountsSignals() {
        val b = TripBuilder(0L, 1_000.0, 3.0)
        var t = 0L
        listOf(0.0, 0.0, 30.0, 60.0, 60.0, 110.0, 110.0, 40.0, 0.0).forEach { kph -> b.speed(t, kph); t += 1_000 }
        b.brake(true); b.brake(true); b.brake(false); b.brake(true)
        b.temp(-2.0)
        b.odometer(1_006.5)
        val trip = b.finish(10 * 60_000L, "t1", "car", zone)!!
        assertEquals(6.5, trip.km, 1e-9)
        assertEquals(10.0, trip.minutes, 1e-9)
        assertEquals(2, trip.brakePresses)
        assertEquals(-2.0, trip.outsideC!!, 1e-9)
        assertTrue(trip.hardEvents!! >= 2) // +30 km/h in 1 s and -70 km/h in 1 s
        assertTrue(trip.idleShare!! > 0.0)
        assertTrue(trip.highSpeedShare!! > 0.0)
    }

    @Test fun tripBuilderFallsBackToSpeedAndDropsTinyMoves() {
        val b = TripBuilder(0L, null, null)
        b.speed(0, 36.0); b.speed(10_000, 36.0); b.speed(20_000, 36.0)
        assertEquals(0.2, b.distanceKm(), 1e-6) // 36 km/h for 20 s
        assertNotNull(b.finish(60_000, "t", "car", zone))
        assertNull(TripBuilder(0L, 500.0, null).finish(20_000, "t", "car", zone))
        // A decreasing odometer is not trusted as distance.
        val c = TripBuilder(0L, 500.0, null).apply { odometer(400.0) }
        assertEquals(0.0, c.distanceKm(), 1e-9)
    }

    @Test fun sameTripIsStoredOnce() {
        val s = stateWith(DrivePattern.COMMUTE)
        val t = s.tripsOf().first()
        assertFalse(s.addTrip(t))
        assertFalse(s.addTrip(t.copy(id = "other")))
    }

    @Test fun tripsAndExclusionsSurviveStorage() {
        val s = stateWith(DrivePattern.CITY)
        s.updateVehicle(s.vehicle.copy(excluded = setOf(4)))
        val back = CareState()
        assertTrue(CareJson.read(CareJson.write(s), back))
        assertEquals(s.trips, back.trips)
        assertEquals(DrivePattern.CITY, back.pattern)
        assertEquals(setOf(4), back.vehicle.excluded)
    }
}
