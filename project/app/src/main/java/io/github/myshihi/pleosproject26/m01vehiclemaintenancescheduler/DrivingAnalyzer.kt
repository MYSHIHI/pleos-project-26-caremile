package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit.DAYS
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * App criteria for matching driving history to the manual's severe conditions. The manual lists the conditions
 * but gives no numbers for them, so these thresholds are the app's own draft criteria (shown to the user and
 * subject to expert review). Once a condition is matched, only the manufacturer's severe interval is applied.
 */
object DrivingCriteria {
    const val WINDOW_DAYS = 30
    const val MIN_TRIPS = 10
    const val SHORT_TRIP_KM = 8.0
    const val SHORT_SHARE = 0.5
    const val IDLE_SHARE = 0.25
    const val CONGESTED_KPH = 20.0
    const val HIGH_SPEED_KPH = 100.0
    const val HIGH_SPEED_SHARE = 0.3
    const val HARD_KPH_PER_S = 10.0
    const val HARD_PER_100KM = 5.0
    const val BRAKES_PER_KM = 1.5
    const val COLD_C = 0.0
    const val COLD_SHARE = 0.3
    const val QUIET_USAGE = 0.25
}

enum class FindingState(val label: String) { DETECTED("해당"), CLEAR("해당 안 함"), NO_DATA("데이터 부족") }

data class Finding(val condition: Int, val state: FindingState, val measured: String, val criterion: String)

data class DrivingProfile(
    val trips: List<Trip>,
    val days: Int,
    val enough: Boolean,
    val totalKm: Double,
    val findings: List<Finding>,
    /** Share of weekdays / weekend days on which the car was driven in each block. */
    val usage: Map<TimeBlock, Double>,
) {
    val detected get() = findings.filter { it.state == FindingState.DETECTED }.map { it.condition }.toSet()
    val quietBlocks get() = if (!enough) emptySet() else usage.filter { it.value < DrivingCriteria.QUIET_USAGE }.keys
    val avgTripKm get() = if (trips.isEmpty()) 0.0 else totalKm / trips.size
    val avgKph get() = trips.sumOf { it.minutes }.let { if (it > 0) totalKm / (it / 60.0) else 0.0 }
}

private fun pct(v: Double) = "${(v * 100).roundToInt()}%"
private fun one(v: Double) = "%.1f".format(v)

object DrivingAnalyzer {
    /** Conditions that driving signals can speak to; the rest need the user's own answer. */
    val detectable = listOf(1, 3, 4, 8, 9, 10)

    fun blockOfTrip(t: Trip): TimeBlock {
        val weekend = t.date.dayOfWeek == DayOfWeek.SATURDAY || t.date.dayOfWeek == DayOfWeek.SUNDAY
        return when {
            weekend && t.startHour < 12 -> TimeBlock.WEEKEND_MORNING
            weekend -> TimeBlock.WEEKEND_AFTERNOON
            t.startHour < 12 -> TimeBlock.WEEKDAY_MORNING
            t.startHour < 17 -> TimeBlock.WEEKDAY_AFTERNOON
            else -> TimeBlock.WEEKDAY_EVENING
        }
    }

    fun analyze(all: List<Trip>, today: LocalDate): DrivingProfile {
        val c = DrivingCriteria
        val trips = all.filter { DAYS.between(it.date, today) in 0 until c.WINDOW_DAYS.toLong() }.sortedBy { it.startMillis }
        val enough = trips.size >= c.MIN_TRIPS
        val km = trips.sumOf { it.km }
        fun metric(cond: Int, criterion: String, values: List<Double>, measured: (List<Double>) -> Pair<Boolean, String>): Finding =
            if (!enough || values.size < c.MIN_TRIPS) Finding(cond, FindingState.NO_DATA, "운행 ${values.size}회 · ${c.MIN_TRIPS}회 이상 필요", criterion)
            else measured(values).let { (hit, text) -> Finding(cond, if (hit) FindingState.DETECTED else FindingState.CLEAR, text, criterion) }

        val findings = listOf(
            metric(1, "${one(c.SHORT_TRIP_KM)} km 이하 운행이 ${pct(c.SHORT_SHARE)} 이상", trips.map { it.km }) { v ->
                val share = v.count { it <= c.SHORT_TRIP_KM }.toDouble() / v.size
                (share >= c.SHORT_SHARE) to "${one(c.SHORT_TRIP_KM)} km 이하 ${pct(share)} (${v.size}회 중 ${v.count { it <= c.SHORT_TRIP_KM }}회)"
            },
            metric(3, "주행 중 정차(0 km/h) 시간 ${pct(c.IDLE_SHARE)} 이상", trips.mapNotNull { it.idleShare }) { v ->
                val share = trips.filter { it.idleShare != null }.let { t -> t.sumOf { it.idleShare!! * it.minutes } / t.sumOf { it.minutes } }
                (share >= c.IDLE_SHARE) to "정차 시간 ${pct(share)} · 평균 ${pct(v.average())}"
            },
            metric(4, "평균 속도 ${c.CONGESTED_KPH.roundToInt()} km/h 이하", trips.map { it.avgKph }) { _ ->
                val avg = trips.sumOf { it.km } / (trips.sumOf { it.minutes } / 60.0)
                (avg <= c.CONGESTED_KPH) to "평균 ${one(avg)} km/h"
            },
            metric(8, "${c.HIGH_SPEED_KPH.roundToInt()} km/h 이상 ${pct(c.HIGH_SPEED_SHARE)} 이상 또는 급가감속 100 km당 ${c.HARD_PER_100KM.roundToInt()}회 이상",
                trips.mapNotNull { it.highSpeedShare }) { _ ->
                val t = trips.filter { it.highSpeedShare != null }
                val high = t.sumOf { it.highSpeedShare!! * it.minutes } / t.sumOf { it.minutes }
                val hardTrips = trips.filter { it.hardEvents != null }
                val per100 = if (hardTrips.sumOf { it.km } > 0) hardTrips.sumOf { it.hardEvents!! } * 100.0 / hardTrips.sumOf { it.km } else 0.0
                (high >= c.HIGH_SPEED_SHARE || per100 >= c.HARD_PER_100KM) to "고속 ${pct(high)} · 급가감속 100 km당 ${one(per100)}회"
            },
            metric(9, "브레이크 조작 1 km당 ${one(c.BRAKES_PER_KM)}회 이상", trips.mapNotNull { it.brakePresses?.toDouble() }) { _ ->
                val t = trips.filter { it.brakePresses != null }
                val perKm = t.sumOf { it.brakePresses!! }.toDouble() / t.sumOf { it.km }.coerceAtLeast(0.1)
                (perKm >= c.BRAKES_PER_KM) to "1 km당 ${one(perKm)}회"
            },
            metric(10, "외기 ${c.COLD_C.roundToInt()}℃ 이하 출발 ${pct(c.COLD_SHARE)} 이상", trips.mapNotNull { it.outsideC }) { v ->
                val share = v.count { it <= c.COLD_C }.toDouble() / v.size
                (share >= c.COLD_SHARE) to "${c.COLD_C.roundToInt()}℃ 이하 ${pct(share)}"
            },
        )

        val weekdays = (0 until c.WINDOW_DAYS).map { today.minusDays(it.toLong()) }
        val weekendCount = weekdays.count { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }.coerceAtLeast(1)
        val weekdayCount = (c.WINDOW_DAYS - weekendCount).coerceAtLeast(1)
        val usage = TimeBlock.entries.associateWith { block ->
            val days = trips.filter { blockOfTrip(it) == block }.map { it.date }.toSet().size
            days.toDouble() / if (block == TimeBlock.WEEKEND_MORNING || block == TimeBlock.WEEKEND_AFTERNOON) weekendCount else weekdayCount
        }
        val span = trips.firstOrNull()?.let { DAYS.between(it.date, today).toInt() + 1 } ?: 0
        return DrivingProfile(trips, span, enough, km, findings, usage)
    }

    /** Rules whose interval the matched conditions change, as "normal → severe" text. */
    fun changes(conditions: Set<Int>): List<Pair<Rule, String>> = RuleBook.rules.mapNotNull { r ->
        val s = r.severe ?: return@mapNotNull null
        val hits = s.conditions.intersect(conditions)
        if (hits.isEmpty()) return@mapNotNull null
        val text = when {
            s.interval != null && r.repeat != null -> "${r.repeat.text} → ${s.interval.text}"
            s.interval != null -> "정기 주기 없음 → ${s.interval.text}"
            else -> "주기는 유지 · ${s.text}"
        }
        r to "$text (조건 ${hits.sorted().joinToString(",")}번)"
    }
}

/**
 * Accumulates one live drive from vehicle signals. Android-free so the trip rules can be tested on the JVM.
 * Distance comes from the odometer when it is available, otherwise from integrated speed.
 */
class TripBuilder(val startMillis: Long, private val startKm: Double?, startTempC: Double?) {
    private var lastAt: Long? = null
    private var lastKph = 0.0
    private var lastHardAt: Long? = null
    private var lastBrake = false
    private var idleMs = 0L
    private var highMs = 0L
    private var sampledMs = 0L
    private var integratedKm = 0.0
    private var samples = 0
    private var brakeSignals = 0
    var hardEvents = 0; private set
    var brakePresses = 0; private set
    var minTempC: Double? = startTempC; private set
    var lastKm: Double? = startKm; private set
    val speedKph get() = lastKph

    fun speed(at: Long, kph: Double) {
        val prev = lastAt
        if (prev != null && at > prev) {
            val dt = (at - prev).coerceAtMost(10_000L)
            sampledMs += dt
            if (lastKph < 1.0) idleMs += dt
            if (lastKph >= DrivingCriteria.HIGH_SPEED_KPH) highMs += dt
            integratedKm += lastKph * dt / 3_600_000.0
            val rate = (kph - lastKph) / (dt / 1000.0)
            val sinceLast = lastHardAt?.let { at - it }
            if (dt >= 300 && abs(rate) >= DrivingCriteria.HARD_KPH_PER_S && (sinceLast == null || sinceLast > 3_000)) { hardEvents++; lastHardAt = at }
        }
        lastAt = at; lastKph = kph.coerceAtLeast(0.0); samples++
    }

    fun brake(pressed: Boolean) { brakeSignals++; if (pressed && !lastBrake) brakePresses++; lastBrake = pressed }
    fun temp(c: Double) { minTempC = minTempC?.let { minOf(it, c) } ?: c }
    fun odometer(km: Double) { lastKm = km }

    fun distanceKm(): Double {
        val s = startKm; val e = lastKm
        return if (s != null && e != null && e >= s && e - s > 0.0) e - s else integratedKm
    }

    fun finish(endMillis: Long, id: String, vehicleId: String, zone: ZoneId = ZoneId.systemDefault()): Trip? {
        val minutes = (endMillis - startMillis) / 60_000.0
        val km = distanceKm()
        if (km < 0.2 && minutes < 1.0) return null
        val start = Instant.ofEpochMilli(startMillis).atZone(zone)
        val speedOk = samples >= 3 && sampledMs > 0
        return Trip(id, vehicleId, startMillis, endMillis, start.toLocalDate(), start.hour, km, minutes.coerceAtLeast(0.1),
            if (speedOk) idleMs.toDouble() / sampledMs else null,
            if (speedOk) highMs.toDouble() / sampledMs else null,
            if (speedOk) hardEvents else null,
            if (brakeSignals > 0) brakePresses else null,
            minTempC, Source.VEHICLE)
    }
}
