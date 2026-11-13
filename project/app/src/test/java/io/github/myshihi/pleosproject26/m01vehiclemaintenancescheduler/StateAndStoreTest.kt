package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StateAndStoreTest {
    private val today = LocalDate.of(2026, 11, 11)
    private fun seeded() = CareState().also { val now = today.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        SampleData.seed(it, today, now)
        // These tests cover planning under normal conditions; driving-history tailoring is covered in DrivingTest.
        SampleData.rebuildDriving(it, SampleData.A, DrivePattern.BALANCED, today, now, 41_600.0)
    }

    @Test fun odometerDecreaseIsIgnoredAndRepeatsDoNotGrowStorage() {
        val s = seeded()
        val km = s.currentKm()!!
        val size = s.readingsOf().size
        assertEquals(ReadingResult.DECREASE, s.addReading(s.vehicleId, km - 1_000.0, Source.SAMPLE, 4_000_000_000_000L, today))
        assertEquals(km, s.currentKm())
        assertEquals(ReadingResult.UNCHANGED, s.addReading(s.vehicleId, km.toDouble(), Source.SAMPLE, 4_000_000_000_001L, today))
        assertEquals(size, s.readingsOf().size)
        assertEquals(ReadingResult.ACCEPTED, s.addReading(s.vehicleId, km + 50.0, Source.SAMPLE, 4_000_000_000_002L, today))
        assertEquals(km + 50, s.currentKm())
    }

    @Test fun sameRecordIsNotAddedTwiceAndVehiclesStaySeparate() {
        val s = seeded()
        val r = Record("x1", SampleData.A, RuleBook.brakeFluid.id, Action.REPLACE, today, 41_000, Source.USER)
        assertTrue(s.addRecord(r))
        assertFalse(s.addRecord(r.copy(id = "x2")))
        assertTrue(s.recordsOf(SampleData.B).isEmpty())
        s.vehicleId = SampleData.B
        assertTrue(s.plans(today).isEmpty())
    }

    @Test fun editingHistoryRecalculatesPlan() {
        val s = seeded()
        val oil = s.records.first { it.itemId == RuleBook.engineOil.id }
        assertEquals(Status.SOON, s.plans(today).first { it.rule.item == RuleBook.engineOil }.status)
        assertTrue(s.editRecord(oil.id, oil.action, today.minusDays(3), 41_500))
        val p = s.plans(today).first { it.rule.item == RuleBook.engineOil }
        assertEquals(Status.OK, p.status)
        assertEquals(51_500, p.dueKm)
        assertTrue(s.records.first { it.id == oil.id }.edited)
    }

    @Test fun fromNewNeedsDeliveryDate() {
        val s = seeded()
        s.vehicleId = SampleData.REAL
        assertFalse(s.markFromNew(RuleBook.brakeFluid.id, Action.REPLACE))
        s.updateVehicle(s.vehicle.copy(deliveryDate = LocalDate.of(2024, 1, 1)))
        assertTrue(s.markFromNew(RuleBook.brakeFluid.id, Action.REPLACE))
    }

    @Test fun storeRoundTripsEverything() {
        val s = seeded()
        s.scenario = Scenario.LOST
        s.dayOffset = 7
        s.updateVehicle(s.vehicle.copy(severe = setOf(1, 4)))
        s.updatePrefs(s.prefsOf().copy(budget = 300_000, auto = AutoBooking(true, 250_000)))
        val visit = s.visitPlan(s.plans(today), today, today.atTime(9, 0)).visits.first()
        s.request(visit, true, 5)
        val text = CareJson.write(s)
        val back = CareState()
        assertTrue(CareJson.read(text, back))
        assertEquals(s.vehicles, back.vehicles)
        assertEquals(s.records, back.records)
        assertEquals(s.readings, back.readings)
        assertEquals(s.bookings, back.bookings)
        assertEquals(s.autoTried, back.autoTried)
        assertEquals(s.prefsOf().budget, back.prefsOf().budget)
        assertEquals(s.prefsOf().auto, back.prefsOf().auto)
        assertEquals(Scenario.LOST, back.scenario)
        assertEquals(7, back.dayOffset)
        assertEquals(s.seq, back.seq)
    }

    @Test fun damagedStoreIsRejected() {
        val s = CareState()
        assertFalse(CareJson.read(null, s))
        assertFalse(CareJson.read("{broken", s))
        assertFalse(CareJson.read("""{"version":99}""", s))
        assertTrue(s.vehicles.isEmpty())
    }
}
