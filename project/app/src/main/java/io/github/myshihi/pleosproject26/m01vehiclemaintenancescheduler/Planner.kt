package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit.DAYS
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class Status(val label: String, val rank: Int) {
    OVERDUE("기한 도래", 0), SOON("곧 도래", 1), NEEDS_HISTORY("이력 확인 필요", 2), NEEDS_ODOMETER("주행거리 확인 필요", 3),
    OK("여유", 4), CONDITION("상태에 따라 점검", 5), NOT_SCHEDULED("정기 주기 없음", 6),
}

data class Rate(val kmPerDay: Double?, val text: String)

data class ItemPlan(
    val rule: Rule,
    val status: Status,
    val interval: Interval?,
    val severeApplied: Boolean,
    val severeNote: String?,
    val base: Record?,
    val dueKm: Int?,
    val dueDate: LocalDate?,
    val remainingKm: Int?,
    val remainingDays: Long?,
    val projectedKmDate: LocalDate?,
    /** Earliest known date by which the service is due (date criterion or projected distance). */
    val deadline: LocalDate?,
    val firstBy: String?,
    val lines: List<String>,
)

data class Price(val won: Int, val confirmed: Boolean)

/** Sample shop. [jobs] maps rule ids the shop handles to a sample price. Slots are offsets from the planning day. */
data class Shop(val id: String, val name: String, val area: String, val jobs: Map<String, Price>, val slots: List<Pair<Int, Int>>)

data class Slot(val shopId: String, val at: LocalDateTime)

data class Visit(
    val shop: Shop,
    val slot: Slot,
    val items: List<ItemPlan>,
    val confirmedCost: Int,
    val unpriced: List<ItemPlan>,
    val withinBudget: Boolean,
    val preferred: Boolean,
    /** The slot falls in a time block when this car is usually parked (from driving history). */
    val quiet: Boolean = false,
) {
    val ruleIds get() = items.map { it.rule.id }.sorted()
    fun requestKey(vehicleId: String) = "$vehicleId|${shop.id}|${slot.at}|${ruleIds.joinToString(",")}"
}

data class VisitPlan(
    val targets: List<ItemPlan>,
    val visits: List<Visit>,
    val unplaced: List<Pair<ItemPlan, String>>,
)

fun blockOf(at: LocalDateTime): TimeBlock {
    val weekend = at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY
    return when {
        weekend && at.hour < 12 -> TimeBlock.WEEKEND_MORNING
        weekend -> TimeBlock.WEEKEND_AFTERNOON
        at.hour < 12 -> TimeBlock.WEEKDAY_MORNING
        at.hour < 17 -> TimeBlock.WEEKDAY_AFTERNOON
        else -> TimeBlock.WEEKDAY_EVENING
    }
}

object Planner {
    const val RATE_WINDOW_DAYS = 120
    const val RATE_MIN_SPAN_DAYS = 14
    const val MAX_VISITS = 3

    /** Average daily distance from readings. Too few or too short observations give no projection. */
    fun rate(readings: List<Reading>, today: LocalDate): Rate {
        val recent = readings.filter { DAYS.between(it.date, today) in 0..RATE_WINDOW_DAYS.toLong() }.sortedBy { it.atMillis }
        if (recent.size < 2) return Rate(null, "운행 기록 ${recent.size}건 · 예상 날짜는 ${RATE_MIN_SPAN_DAYS}일 이상 기록이 쌓이면 계산해요")
        val span = DAYS.between(recent.first().date, recent.last().date)
        if (span < RATE_MIN_SPAN_DAYS) return Rate(null, "기록 기간 ${span}일 · ${RATE_MIN_SPAN_DAYS}일 이상 쌓이면 예상 날짜를 계산해요")
        val km = recent.last().km - recent.first().km
        if (km < 0) return Rate(null, "주행거리 기록이 일관되지 않아 예상하지 않아요")
        val perDay = km / span
        return Rate(perDay, "최근 ${span}일 하루 평균 약 ${perDay.roundToInt()} km")
    }

    /** Latest record that resets this rule. An inspection never resets a replacement interval. */
    fun baseFor(rule: Rule, records: List<Record>): Record? = records
        .filter { it.itemId == rule.item.id && (it.action == rule.action || (rule.action == Action.INSPECT && it.action == Action.REPLACE)) }
        .maxWithOrNull(compareBy<Record>({ it.date }, { it.km }))

    fun plan(vehicle: Vehicle, records: List<Record>, currentKm: Int?, rate: Rate, today: LocalDate, prefs: Preferences): List<ItemPlan> =
        RuleBook.rulesFor(vehicle).map { planItem(it, vehicle, records.filter { r -> r.vehicleId == vehicle.id }, currentKm, rate, today, prefs) }
            .sortedWith(compareBy<ItemPlan>({ it.status.rank }, { it.deadline ?: LocalDate.MAX }, { it.remainingKm ?: Int.MAX_VALUE }))

    fun planItem(rule: Rule, vehicle: Vehicle, records: List<Record>, currentKm: Int?, rate: Rate, today: LocalDate, prefs: Preferences): ItemPlan {
        val hits = rule.severe?.conditions?.intersect(vehicle.severe)?.sorted().orEmpty()
        val severeNumeric = hits.isNotEmpty() && rule.severe?.interval != null
        val severeNote = if (hits.isNotEmpty() && !severeNumeric)
            "가혹 조건 ${hits.joinToString(",")}번: ${rule.severe!!.text} · 방문할 때 함께 점검을 요청하세요" else null
        val base = baseFor(rule, records)
        val interval = when {
            severeNumeric -> rule.severe!!.interval
            base?.fromNew == true && rule.first != null -> rule.first
            else -> rule.repeat
        }
        val lines = mutableListOf<String>()
        val condition = if (severeNumeric) "가혹 조건 ${hits.joinToString(",")}번 적용" else "통상 조건"
        if (interval == null) {
            lines += "기준: ${rule.normalText}"
            return ItemPlan(rule, if (severeNote != null) Status.CONDITION else Status.NOT_SCHEDULED, null, false, severeNote,
                base, null, null, null, null, null, null, null, lines)
        }
        lines += "주기: ${interval.text} ($condition${if (interval === rule.first) " · 최초 주기" else ""})"
        if (base == null) {
            lines += "마지막 ${rule.actionLabel} 기록이 없어요. 기록을 보완하거나 출고 후 하지 않았는지 확인해 주세요."
            return ItemPlan(rule, Status.NEEDS_HISTORY, interval, severeNumeric, severeNote, null, null, null, null, null, null, null, null, lines)
        }
        lines += if (base.fromNew) "기준점: 출고일 ${base.date} · 0 km (출고 후 미실시, ${base.source.label})"
        else "기준점: ${base.date} · ${fmtKm(base.km)} km ${if (base.action == rule.action) rule.actionLabel else "교체"} (${base.source.label}${if (base.edited) " · 수정됨" else ""})"
        val dueKm = interval.km?.let { base.km + it }
        val dueDate = interval.months?.let { base.date.plusMonths(it.toLong()) }
        val remainingKm = if (dueKm != null && currentKm != null) dueKm - currentKm else null
        val remainingDays = dueDate?.let { DAYS.between(today, it) }
        val perDay = rate.kmPerDay
        val projected = when {
            remainingKm == null -> null
            remainingKm <= 0 -> today
            perDay != null && perDay > 0.0 -> today.plusDays(ceil(remainingKm / perDay).toLong())
            else -> null
        }
        if (dueKm != null) lines += when {
            remainingKm == null -> "거리: ${fmtKm(dueKm)} km 기준 · 현재 주행거리 확인 필요"
            remainingKm > 0 -> "거리: ${fmtKm(dueKm)} km까지 ${fmtKm(remainingKm)} km 남음"
            else -> "거리: ${fmtKm(dueKm)} km 기준 ${fmtKm(-remainingKm)} km 지남"
        }
        if (dueDate != null) lines += if (remainingDays!! > 0) "기간: ${dueDate}까지 ${remainingDays}일 남음" else "기간: $dueDate 기준 ${-remainingDays}일 지남"
        if (dueKm != null && remainingKm != null && remainingKm > 0) lines += if (projected != null)
            "예상: 지금 운행량이면 ${projected}쯤 거리 기준 도달" else "예상 날짜: ${rate.text}"
        val deadline = listOfNotNull(dueDate, projected).minOrNull()
        val firstBy = when {
            dueKm != null && dueDate == null -> "거리 기준"
            dueKm == null -> "기간 기준"
            projected == null -> null
            !projected.isAfter(dueDate) -> "거리 기준"
            else -> "기간 기준"
        }
        lines += if (firstBy != null) "먼저 도래: $firstBy" else "먼저 도래: 운행 기록이 쌓이면 거리·기간 중 먼저 오는 쪽을 알려드려요"
        val reached = (remainingKm != null && remainingKm <= 0) || (remainingDays != null && remainingDays <= 0)
        val status = when {
            reached -> Status.OVERDUE
            dueKm != null && currentKm == null -> Status.NEEDS_ODOMETER
            (remainingKm != null && remainingKm <= prefs.soonKm) || (remainingDays != null && remainingDays <= prefs.soonDays) ||
                (projected != null && DAYS.between(today, projected) <= prefs.soonDays) -> Status.SOON
            else -> Status.OK
        }
        return ItemPlan(rule, status, interval, severeNumeric, severeNote, base, dueKm, dueDate, remainingKm, remainingDays, projected, deadline, firstBy, lines)
    }

    fun isTarget(p: ItemPlan, today: LocalDate, prefs: Preferences) = p.status == Status.OVERDUE || p.status == Status.SOON ||
        (p.status == Status.OK && p.deadline != null && !p.deadline.isAfter(today.plusDays(prefs.horizonDays.toLong())))

    /** Latest acceptable visit day. Overdue work is only placed in near slots; it is never pushed later for bundling. */
    fun latest(p: ItemPlan, today: LocalDate, prefs: Preferences): LocalDate = when {
        p.status == Status.OVERDUE -> today.plusDays(prefs.overdueDays.toLong())
        p.deadline != null -> p.deadline
        else -> today.plusDays(prefs.soonDays.toLong())
    }

    /** Earliest acceptable visit day: services are not pulled forward more than the allowed advance. */
    fun earliest(p: ItemPlan, today: LocalDate, prefs: Preferences): LocalDate =
        if (p.status == Status.OVERDUE) today else latest(p, today, prefs).minusDays(prefs.advanceDays.toLong())

    fun slots(shop: Shop, today: LocalDate, now: LocalDateTime): List<Slot> =
        shop.slots.map { (day, hour) -> Slot(shop.id, today.plusDays(day.toLong()).atTime(hour, 0)) }.filter { it.at.isAfter(now) }

    fun candidates(targets: List<ItemPlan>, shops: List<Shop>, prefs: Preferences, today: LocalDate, now: LocalDateTime,
                   quiet: Set<TimeBlock> = emptySet()): List<Visit> {
        val limit = today.plusDays(prefs.horizonDays.toLong())
        val out = mutableListOf<Visit>()
        for (shop in shops) for (slot in slots(shop, today, now)) {
            val day = slot.at.toLocalDate()
            if (blockOf(slot.at) !in prefs.blocks || day.isAfter(limit)) continue
            val items = targets.filter { it.rule.id in shop.jobs && !day.isBefore(earliest(it, today, prefs)) && !day.isAfter(latest(it, today, prefs)) }
            if (items.isEmpty()) continue
            val prices = items.map { shop.jobs.getValue(it.rule.id) }
            val cost = prices.filter { it.confirmed }.sumOf { it.won }
            out += Visit(shop, slot, items, cost, items.filter { !shop.jobs.getValue(it.rule.id).confirmed }, cost <= prefs.budget,
                shop.id in prefs.preferredShops, blockOf(slot.at) in quiet)
        }
        return out.sortedWith(compareByDescending<Visit> { it.items.size }.thenByDescending { it.withinBudget }.thenByDescending { it.preferred }
            .thenByDescending { it.quiet }.thenBy { it.unpriced.size }.thenBy { it.slot.at })
    }

    /** Greedy bundling: the visit covering most due work first, then the rest, at most [MAX_VISITS] visits. */
    fun visits(plans: List<ItemPlan>, booked: Set<String>, shops: List<Shop>, prefs: Preferences, today: LocalDate, now: LocalDateTime,
               quiet: Set<TimeBlock> = emptySet()): VisitPlan {
        val targets = plans.filter { isTarget(it, today, prefs) && it.rule.id !in booked }
        val remaining = targets.toMutableList()
        val visits = mutableListOf<Visit>()
        while (remaining.isNotEmpty() && visits.size < MAX_VISITS) {
            val best = candidates(remaining, shops, prefs, today, now, quiet).firstOrNull() ?: break
            visits += best
            remaining.removeAll { p -> best.items.any { it.rule.id == p.rule.id } }
        }
        val unplaced = remaining.map { p ->
            p to if (shops.none { p.rule.id in it.jobs }) "취급하는 정비소가 목록에 없어요 · 직접 확인이 필요해요"
            else "기한 안에 고른 시간대의 빈자리가 없어요 · 시간대를 넓히거나 따로 방문해 주세요"
        }
        return VisitPlan(targets, visits.sortedBy { it.slot.at }, unplaced)
    }
}
