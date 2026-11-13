package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import java.time.LocalDateTime

enum class BookingState(val label: String, val active: Boolean) {
    REQUESTED("요청 보냄 · 응답 기다리는 중", true),
    PENDING("정비소 접수 · 확정 대기", true),
    UNKNOWN("응답 없음 · 예약 조회 필요", true),
    CONFIRMED("예약 확정", true),
    CHANGE_REQUESTED("일정 변경 요청 중", true),
    REJECTED("정비소가 거절했어요", false),
    EXPIRED("빈자리가 마감됐어요", false),
    CANCELLED("취소했어요", false),
    COMPLETED("정비 결과 반영 완료", false),
}

/** How the local sample provider answers the next request. Nothing is sent outside the app. */
enum class Scenario(val label: String) {
    CONFIRM("바로 확정"), WAIT("대기 후 확정"), REJECT("거절"), EXPIRE("빈자리 마감"), LOST("응답 유실"),
}

data class Event(val atMillis: Long, val text: String)

data class Booking(
    val id: String,
    val requestKey: String,
    val vehicleId: String,
    val shopId: String,
    val shopName: String,
    val at: LocalDateTime,
    val ruleIds: List<String>,
    val confirmedCost: Int,
    val unpriced: Int,
    val auto: Boolean,
    val scenario: Scenario,
    val state: BookingState,
    val updatedAt: Long,
    val events: List<Event>,
    val changeTo: LocalDateTime? = null,
) {
    fun next(state: BookingState, now: Long, text: String, at: LocalDateTime = this.at, changeTo: LocalDateTime? = null) =
        copy(state = state, updatedAt = now, events = events + Event(now, text), at = at, changeTo = changeTo)
}

sealed class RequestResult {
    data class Created(val booking: Booking) : RequestResult()
    data class Duplicate(val booking: Booking) : RequestResult()
    data class Blocked(val reason: String) : RequestResult()
}

object BookingFlow {
    const val STEP_MS = 2_500L

    fun request(all: List<Booking>, vehicleId: String, visit: Visit, scenario: Scenario, auto: Boolean, id: String, now: Long): RequestResult {
        val key = visit.requestKey(vehicleId)
        all.firstOrNull { it.vehicleId == vehicleId && it.requestKey == key && it.state.active }?.let { return RequestResult.Duplicate(it) }
        all.firstOrNull { it.vehicleId == vehicleId && it.state.active }?.let {
            return RequestResult.Blocked("진행 중인 예약(${it.shopName} · ${it.state.label})을 먼저 마무리해 주세요")
        }
        val text = if (auto) "허용한 조건 안이라 자동으로 예약을 요청했어요" else "예약을 요청했어요"
        return RequestResult.Created(Booking(id, key, vehicleId, visit.shop.id, visit.shop.name, visit.slot.at, visit.ruleIds,
            visit.confirmedCost, visit.unpriced.size, auto, scenario, BookingState.REQUESTED, now, listOf(Event(now, text))))
    }

    /** Sample provider progress. Requests and waits never turn into "confirmed" without a provider answer. */
    fun tick(b: Booking, now: Long): Booking {
        val elapsed = now - b.updatedAt
        return when (b.state) {
            BookingState.REQUESTED -> if (elapsed < STEP_MS) b else when (b.scenario) {
                Scenario.CONFIRM -> b.next(BookingState.CONFIRMED, now, "정비소가 예약을 확정했어요")
                Scenario.WAIT -> b.next(BookingState.PENDING, now, "정비소가 접수했어요 · 확정을 기다려요")
                Scenario.REJECT -> b.next(BookingState.REJECTED, now, "정비소가 이 일정을 받을 수 없다고 답했어요 · 자동으로 다시 요청하지 않아요")
                Scenario.EXPIRE -> b.next(BookingState.EXPIRED, now, "요청 사이에 빈자리가 마감됐어요 · 다른 후보를 골라 주세요")
                Scenario.LOST -> b.next(BookingState.UNKNOWN, now, "응답을 받지 못했어요 · 중복 예약을 막기 위해 새 요청 대신 예약 조회가 필요해요")
            }
            BookingState.PENDING -> if (elapsed < STEP_MS * 2) b else b.next(BookingState.CONFIRMED, now, "정비소가 예약을 확정했어요")
            BookingState.CHANGE_REQUESTED -> if (elapsed < STEP_MS || b.changeTo == null) b
                else b.next(BookingState.CONFIRMED, now, "변경한 일정(${b.changeTo})이 확정됐어요", at = b.changeTo)
            else -> b
        }
    }

    /** Lost response: look the request up instead of sending a new one. The sample provider did receive it. */
    fun lookup(b: Booking, now: Long): Booking = if (b.state != BookingState.UNKNOWN) b
        else b.next(BookingState.PENDING, now, "예약 조회: 정비소에 기존 요청이 접수돼 있어요 · 새 요청을 만들지 않았어요")

    fun cancel(b: Booking, now: Long): Booking = if (!b.state.active) b else b.next(BookingState.CANCELLED, now, "예약을 취소했어요")

    fun change(b: Booking, to: LocalDateTime, now: Long): Booking = if (b.state != BookingState.CONFIRMED || to == b.at) b
        else b.next(BookingState.CHANGE_REQUESTED, now, "일정 변경을 요청했어요 → $to", changeTo = to)

    /** Reasons the visit may not be booked automatically. Empty means the user's permission covers it. */
    fun autoBlockers(visit: Visit, prefs: Preferences, odometerFresh: Boolean, hasActive: Boolean): List<String> = buildList {
        if (!prefs.auto.enabled) add("자동 예약이 꺼져 있어요")
        if (visit.shop.id !in prefs.preferredShops) add("선호 정비소가 아니에요")
        if (visit.unpriced.isNotEmpty()) add("가격 미확정 ${visit.unpriced.size}건")
        if (visit.confirmedCost > prefs.auto.maxCost) add("자동 예약 한도 ${fmtWon(prefs.auto.maxCost)} 초과")
        if (!visit.withinBudget) add("예산 ${fmtWon(prefs.budget)} 초과")
        if (!odometerFresh) add("오늘 확인한 주행거리가 없어요")
        if (hasActive) add("진행 중인 예약이 있어요")
    }
}
