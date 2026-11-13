package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Virtual vehicles, history and shops for the demo. Shop names, prices and free slots are samples, not real offers.
 * Sample history is placed relative to the day the sample is created so the demo always shows due work.
 */
object SampleData {
    const val A = "sample-a"
    const val B = "sample-b"
    const val REAL = "real-car"

    private fun p(won: Int, confirmed: Boolean = true) = Price(won, confirmed)

    val shops = listOf(
        Shop("shop_a", "샘플 A 정비소", "집 근처 · 일반 정비", mapOf(
            "engine_oil.replace" to p(89_000), "cabin_filter.replace" to p(25_000), "tire_rotation.replace" to p(20_000),
            "brake_pad.inspect" to p(10_000), "air_cleaner.inspect" to p(5_000), "air_cleaner.replace" to p(28_000),
            "aircon.inspect" to p(15_000), "hsg_belt.inspect" to p(10_000), "brake_fluid.replace" to p(45_000, false),
        ), listOf(1 to 10, 1 to 19, 2 to 19, 3 to 10, 4 to 19, 6 to 10, 6 to 19, 9 to 19, 10 to 10, 13 to 19, 16 to 10, 20 to 19)),
        Shop("shop_b", "샘플 B 서비스센터", "제조사 협력 · 전 항목", mapOf(
            "engine_oil.replace" to p(99_000), "cabin_filter.replace" to p(30_000), "tire_rotation.replace" to p(25_000),
            "brake_pad.inspect" to p(0), "air_cleaner.inspect" to p(0), "air_cleaner.replace" to p(32_000),
            "aircon.inspect" to p(20_000), "hsg_belt.inspect" to p(0), "hsg_belt.replace" to p(0, false),
            "brake_fluid.replace" to p(55_000), "coolant.replace" to p(0, false), "spark_plug.replace" to p(0, false),
            "atf.replace" to p(0, false),
        ), listOf(5 to 10, 5 to 14, 8 to 10, 12 to 14, 15 to 10, 19 to 10, 26 to 14)),
        Shop("shop_c", "샘플 C 타이어 전문점", "타이어·제동 점검", mapOf(
            "tire_rotation.replace" to p(15_000), "brake_pad.inspect" to p(0),
        ), listOf(1 to 18, 2 to 11, 3 to 18, 7 to 11, 8 to 18)),
    )

    fun shop(id: String) = shops.find { it.id == id }

    fun seed(state: CareState, today: LocalDate, nowMillis: Long) {
        state.vehicles.clear(); state.records.clear(); state.readings.clear(); state.prefs.clear()
        state.bookings.clear(); state.autoTried.clear(); state.trips.clear()
        state.dayOffset = 0; state.scenario = Scenario.WAIT; state.seq = 0
        val delivery = today.minusYears(3).minusMonths(8)
        state.vehicles += Vehicle(A, "가상 차량 A", RuleBook.MODEL, RuleBook.YEAR, "하이브리드", delivery, sample = true, profileConfirmed = true)
        state.vehicles += Vehicle(B, "가상 차량 B", "기타 차종", 2021, "가솔린", today.minusYears(5), sample = true, profileConfirmed = true)
        state.vehicles += Vehicle(REAL, "내 차량 (실차 연결)", RuleBook.MODEL, RuleBook.YEAR, "하이브리드", null, sample = false, profileConfirmed = false)
        state.vehicleId = A
        state.sampleMode = true
        state.prefs[A] = Preferences(preferredShops = setOf("shop_a"))
        state.prefs[B] = Preferences()
        state.prefs[REAL] = Preferences()

        val day = 86_400_000L
        state.pattern = DrivePattern.COMMUTE
        rebuildDriving(state, A, DrivePattern.COMMUTE, today, nowMillis, 41_600.0)
        state.readings.getOrPut(B) { mutableListOf() } += Reading(63_250.0, nowMillis - day, today.minusDays(1), Source.SAMPLE)

        val visit = today.minusMonths(11)
        fun add(item: Item, action: Action, date: LocalDate, km: Int, fromNew: Boolean = false) =
            state.addRecord(Record(state.nextId("r"), A, item.id, action, date, km, Source.SAMPLE, fromNew = fromNew))
        add(RuleBook.engineOil, Action.REPLACE, visit, 32_400)
        add(RuleBook.hsgBelt, Action.INSPECT, visit, 32_400)
        add(RuleBook.airCleaner, Action.INSPECT, visit, 32_400)
        add(RuleBook.tireRotation, Action.REPLACE, visit, 32_400)
        add(RuleBook.brakePad, Action.INSPECT, visit, 32_400)
        add(RuleBook.airCleaner, Action.REPLACE, today.minusYears(2), 12_300)
        add(RuleBook.cabinFilter, Action.REPLACE, today.minusMonths(15), 27_000)
        add(RuleBook.aircon, Action.INSPECT, today.minusMonths(13), 29_800)
        add(RuleBook.coolant, Action.REPLACE, delivery, 0, fromNew = true)
        add(RuleBook.sparkPlug, Action.REPLACE, delivery, 0, fromNew = true)
        add(RuleBook.hsgBelt, Action.REPLACE, delivery, 0, fromNew = true)
    }

    private data class Spec(val hour: Int, val minute: Int, val km: Double, val minutes: Double, val everyOther: Boolean = false)
    private data class Style(val idle: Double, val high: Double, val hardPer100: Double, val brakesPerKm: Double, val tempC: Double,
                             val weekday: List<Spec>, val weekend: List<Spec>)

    /** Sample driving styles. Values are invented for the demo and labelled as samples in the app. */
    private fun style(p: DrivePattern) = when (p) {
        DrivePattern.BALANCED -> Style(0.10, 0.08, 1.0, 0.6, 8.0,
            listOf(Spec(8, 0, 14.0, 22.0), Spec(18, 30, 15.0, 25.0)), listOf(Spec(11, 0, 35.0, 45.0)))
        DrivePattern.COMMUTE -> Style(0.18, 0.01, 1.5, 1.0, 6.0,
            listOf(Spec(7, 50, 6.2, 15.0), Spec(18, 40, 6.5, 16.0), Spec(20, 10, 2.8, 8.0, everyOther = true)),
            listOf(Spec(13, 30, 4.0, 11.0), Spec(15, 30, 22.0, 35.0)))
        DrivePattern.CITY -> Style(0.32, 0.0, 2.0, 2.4, 6.0,
            listOf(Spec(8, 0, 11.0, 48.0), Spec(18, 30, 12.0, 55.0)), listOf(Spec(14, 0, 9.0, 35.0)))
        DrivePattern.HIGHWAY -> Style(0.04, 0.42, 7.0, 0.3, 6.0,
            listOf(Spec(7, 30, 42.0, 32.0), Spec(19, 0, 43.0, 34.0)), listOf(Spec(10, 0, 120.0, 85.0)))
    }

    /** Sample drives for one day, deterministic per date and pattern. */
    fun tripsOn(vehicleId: String, pattern: DrivePattern, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Trip> {
        val st = style(pattern)
        val rnd = Random(date.toEpochDay() * 31 + pattern.ordinal)
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        fun j(v: Double, spread: Double = 0.12) = v * (1 + (rnd.nextDouble() * 2 - 1) * spread)
        return (if (weekend) st.weekend else st.weekday).filter { !it.everyOther || date.toEpochDay() % 2 == 0L }.mapIndexed { i, sp ->
            val start = date.atTime(sp.hour, sp.minute).plusMinutes(rnd.nextLong(-10, 11)).atZone(zone)
            val km = j(sp.km); val minutes = j(sp.minutes, 0.1)
            Trip("s-$vehicleId-$date-$i-${pattern.name}", vehicleId, start.toInstant().toEpochMilli(),
                start.plusSeconds((minutes * 60).toLong()).toInstant().toEpochMilli(), date, start.hour, km, minutes,
                j(st.idle, 0.2).coerceIn(0.0, 0.95), j(st.high, 0.2).coerceIn(0.0, 0.95),
                (km * j(st.hardPer100, 0.3) / 100).roundToInt(), (km * j(st.brakesPerKm, 0.2)).roundToInt(), j(st.tempC, 0.5), Source.SAMPLE)
        }
    }

    /**
     * Replaces the vehicle's sample driving (last 30 days of trips, 60 days of odometer readings) with [pattern],
     * ending at [endKm] today. Maintenance records and bookings are kept.
     */
    fun rebuildDriving(state: CareState, vehicleId: String, pattern: DrivePattern, today: LocalDate, nowMillis: Long, endKm: Double) {
        state.trips.removeAll { it.vehicleId == vehicleId && it.source == Source.SAMPLE }
        val trips = (29 downTo 0).flatMap { tripsOn(vehicleId, pattern, today.minusDays(it.toLong())) }.filter { it.endMillis <= nowMillis }
        trips.forEach { state.addTrip(it) }
        val readings = mutableListOf<Reading>()
        val avg = trips.sumOf { it.km } / 30.0
        var km = endKm - trips.sumOf { it.km } - avg * 30
        val zone = ZoneId.systemDefault()
        for (d in 59 downTo 30) {
            km += avg
            val date = today.minusDays(d.toLong())
            readings += Reading(km, date.atTime(22, 0).atZone(zone).toInstant().toEpochMilli(), date, Source.SAMPLE)
        }
        for (d in 29 downTo 0) {
            val date = today.minusDays(d.toLong())
            val dayTrips = trips.filter { it.date == date }
            km += dayTrips.sumOf { it.km }
            val at = if (d == 0) nowMillis else date.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
            readings += Reading(if (d == 0) endKm else km, at, date, Source.SAMPLE)
        }
        state.readings[vehicleId] = readings
        state.pattern = pattern
    }
}
