package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import java.time.LocalDate

/** ● 교체 / ○ 점검 in the manufacturer's table. */
enum class Action(val label: String) { REPLACE("교체"), INSPECT("점검") }

/** Where a value came from. Sample values are always shown as samples. */
enum class Source(val label: String) {
    VEHICLE("차량 신호"), USER("사용자 확인"), SHOP_RESULT("정비 결과 확인"), SAMPLE("샘플")
}

/** One manufacturer interval. A null criterion is not given by the table. */
data class Interval(val km: Int?, val months: Int?) {
    val text: String get() = listOfNotNull(km?.let { "${fmtKm(it)} km" }, months?.let(::monthsText)).joinToString(" 또는 ")
}

/** Severe-condition row. A null interval means "상태에 따라 수시 점검" without a number. */
data class SevereRule(val interval: Interval?, val conditions: Set<Int>, val text: String)

data class Item(val id: String, val name: String)

data class Rule(
    val item: Item,
    val action: Action,
    /** Interval for the very first service from new, when the table states one separately. */
    val first: Interval?,
    /** Repeating interval under normal conditions; null when the table gives no periodic service. */
    val repeat: Interval?,
    val severe: SevereRule? = null,
    val normalText: String,
    val actionLabel: String = action.label,
) {
    val id get() = "${item.id}.${action.name.lowercase()}"
    val title get() = "${item.name} $actionLabel"
}

data class Vehicle(
    val id: String,
    val name: String,
    val model: String,
    val year: Int,
    val powertrain: String,
    val deliveryDate: LocalDate?,
    val sample: Boolean,
    /** The user confirmed model/year so the matching manufacturer table may be applied. */
    val profileConfirmed: Boolean,
    /** Severe driving conditions (1..12) the user selected directly for this car. */
    val severe: Set<Int> = emptySet(),
    /** Conditions detected from driving history that the user chose not to apply. */
    val excluded: Set<Int> = emptySet(),
)

/** One drive between leaving Park and returning to Park. Null metrics mean the signal was not available. */
data class Trip(
    val id: String,
    val vehicleId: String,
    val startMillis: Long,
    val endMillis: Long,
    val date: LocalDate,
    val startHour: Int,
    val km: Double,
    val minutes: Double,
    val idleShare: Double?,
    val highSpeedShare: Double?,
    val hardEvents: Int?,
    val brakePresses: Int?,
    val outsideC: Double?,
    val source: Source,
) {
    val avgKph get() = if (minutes > 0) km / (minutes / 60.0) else 0.0
}

enum class DrivePattern(val label: String, val note: String) {
    BALANCED("고른 운행", "출퇴근 10~15 km · 주말 나들이"),
    COMMUTE("출퇴근 단거리", "짧은 거리 위주 · 하루 여러 번"),
    CITY("도심 정체", "느린 평균 속도 · 잦은 정지"),
    HIGHWAY("장거리 고속", "고속도로 위주 · 긴 운행"),
}

data class Record(
    val id: String,
    val vehicleId: String,
    val itemId: String,
    val action: Action,
    val date: LocalDate,
    val km: Int,
    val source: Source,
    /** User-confirmed "not serviced since delivery": baseline is delivery date at 0 km with the first interval. */
    val fromNew: Boolean = false,
    val bookingId: String? = null,
    val edited: Boolean = false,
) {
    val dedupeKey get() = listOf(vehicleId, itemId, action, date, km, fromNew).joinToString("|")
}

data class Reading(val km: Double, val atMillis: Long, val date: LocalDate, val source: Source)

enum class TimeBlock(val label: String) {
    WEEKDAY_MORNING("평일 오전"), WEEKDAY_AFTERNOON("평일 오후"), WEEKDAY_EVENING("평일 저녁"),
    WEEKEND_MORNING("주말 오전"), WEEKEND_AFTERNOON("주말 오후");
}

data class AutoBooking(val enabled: Boolean = false, val maxCost: Int = 150_000)

/** User conditions used by the visit planner and the booking permission check. */
data class Preferences(
    val blocks: Set<TimeBlock> = setOf(TimeBlock.WEEKDAY_EVENING, TimeBlock.WEEKEND_MORNING),
    val budget: Int = 200_000,
    val preferredShops: Set<String> = emptySet(),
    val auto: AutoBooking = AutoBooking(),
    /** Planner settings, shown to the user as app settings rather than manufacturer facts. */
    val soonDays: Int = 30,
    val soonKm: Int = 1_000,
    val advanceDays: Int = 30,
    val overdueDays: Int = 7,
    val horizonDays: Int = 45,
)

fun fmtKm(km: Int) = "%,d".format(km)
fun fmtWon(won: Int) = "%,d원".format(won)
fun monthsText(months: Int) = if (months % 12 == 0) "${months / 12}년" else "${months}개월"
