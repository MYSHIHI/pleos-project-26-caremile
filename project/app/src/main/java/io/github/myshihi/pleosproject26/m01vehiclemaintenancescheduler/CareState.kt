package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import java.time.LocalDate
import java.time.LocalDateTime

enum class ReadingResult { ACCEPTED, UNCHANGED, DECREASE }

/** Android-independent app state. Every vehicle keeps its own records, readings, conditions and bookings. */
class CareState {
    val vehicles = mutableListOf<Vehicle>()
    var vehicleId = ""
    val records = mutableListOf<Record>()
    val readings = mutableMapOf<String, MutableList<Reading>>()
    val prefs = mutableMapOf<String, Preferences>()
    val bookings = mutableListOf<Booking>()
    val trips = mutableListOf<Trip>()
    /** Request keys already tried automatically; a rejected or expired request is never retried on its own. */
    val autoTried = mutableSetOf<String>()
    var sampleMode = true
    var dayOffset = 0
    var scenario = Scenario.WAIT
    var pattern = DrivePattern.COMMUTE
    var seq = 0L

    val vehicle get() = vehicles.first { it.id == vehicleId }
    fun nextId(prefix: String) = "$prefix${++seq}"
    fun prefsOf(id: String = vehicleId) = prefs[id] ?: Preferences()
    fun recordsOf(id: String = vehicleId) = records.filter { it.vehicleId == id }
    fun readingsOf(id: String = vehicleId): List<Reading> = readings[id].orEmpty()
    fun lastReading(id: String = vehicleId) = readingsOf(id).maxByOrNull { it.atMillis }
    fun currentKm(id: String = vehicleId) = lastReading(id)?.km?.toInt()
    fun activeBooking(id: String = vehicleId) = bookings.lastOrNull { it.vehicleId == id && it.state.active }
    fun bookingsOf(id: String = vehicleId) = bookings.filter { it.vehicleId == id }
    fun odometerFresh(today: LocalDate, id: String = vehicleId) = lastReading(id)?.date == today

    fun tripsOf(id: String = vehicleId) = trips.filter { it.vehicleId == id }
    fun profile(today: LocalDate, id: String = vehicleId) = DrivingAnalyzer.analyze(tripsOf(id), today)

    /** Conditions in effect: the user's own choices plus those matched from driving history, minus exclusions. */
    fun effectiveSevere(today: LocalDate, v: Vehicle = vehicle): Set<Int> = v.severe + (profile(today, v.id).detected - v.excluded)

    /** Adds a finished drive once; the same trip id is never counted twice. */
    fun addTrip(t: Trip): Boolean {
        if (trips.any { it.id == t.id || (it.vehicleId == t.vehicleId && it.startMillis == t.startMillis) }) return false
        trips += t
        val mine = trips.filter { it.vehicleId == t.vehicleId }.sortedBy { it.startMillis }
        if (mine.size > 400) trips.removeAll(mine.take(mine.size - 400).toSet())
        return true
    }

    fun updateVehicle(v: Vehicle) { vehicles.replaceAll { if (it.id == v.id) v else it } }
    fun updatePrefs(p: Preferences, id: String = vehicleId) { prefs[id] = p }

    /**
     * A decrease is reported and ignored (trip reset / signal error); it never looks like a part replacement.
     * Repeated equal values on the same day only refresh the timestamp so storage stays bounded.
     */
    fun addReading(id: String, km: Double, source: Source, atMillis: Long, date: LocalDate): ReadingResult {
        val list = readings.getOrPut(id) { mutableListOf() }
        val last = list.maxByOrNull { it.atMillis }
        if (last != null && km < last.km - 0.5) return ReadingResult.DECREASE
        if (last != null && km - last.km < 1.0 && last.date == date) {
            list.remove(last); list += last.copy(atMillis = atMillis)
            return ReadingResult.UNCHANGED
        }
        list += Reading(km, atMillis, date, source)
        while (list.size > 240) list.removeAt(0)
        return ReadingResult.ACCEPTED
    }

    /** Adds a record unless an identical one already exists (same item, work, day, distance). */
    fun addRecord(r: Record): Boolean {
        if (records.any { it.id == r.id || it.dedupeKey == r.dedupeKey }) return false
        records += r
        return true
    }

    fun editRecord(id: String, action: Action, date: LocalDate, km: Int): Boolean {
        val old = records.find { it.id == id } ?: return false
        val updated = old.copy(action = action, date = date, km = km, edited = true)
        if (records.any { it.id != id && it.dedupeKey == updated.dedupeKey }) return false
        records.replaceAll { if (it.id == id) updated else it }
        return true
    }

    fun deleteRecord(id: String) { records.removeAll { it.id == id } }

    fun markFromNew(itemId: String, action: Action): Boolean {
        val delivery = vehicle.deliveryDate ?: return false
        return addRecord(Record(nextId("r"), vehicleId, itemId, action, delivery, 0, Source.USER, fromNew = true))
    }

    /**
     * Reflects only the work the user confirmed as actually performed. A booking by itself never creates a record,
     * and applying the same result twice adds nothing.
     */
    fun applyResult(bookingId: String, performed: List<String>, date: LocalDate, km: Int, now: Long): Int {
        val b = bookings.find { it.id == bookingId } ?: return 0
        if (b.state != BookingState.CONFIRMED) return 0
        var added = 0
        performed.filter { it in b.ruleIds }.forEach { ruleId ->
            val rule = RuleBook.rule(ruleId) ?: return@forEach
            if (addRecord(Record("$bookingId:$ruleId", b.vehicleId, rule.item.id, rule.action, date, km, Source.SHOP_RESULT, bookingId = bookingId))) added++
        }
        val skipped = b.ruleIds.size - performed.count { it in b.ruleIds }
        replaceBooking(b.next(BookingState.COMPLETED, now, "정비 결과 반영: 수행 ${added}건" + if (skipped > 0) " · 미수행 ${skipped}건은 계속 관리해요" else ""))
        return added
    }

    fun replaceBooking(b: Booking) { bookings.replaceAll { if (it.id == b.id) b else it } }

    fun request(visit: Visit, auto: Boolean, now: Long): RequestResult {
        val result = BookingFlow.request(bookings, vehicleId, visit, scenario, auto, nextId("b"), now)
        if (result is RequestResult.Created) bookings += result.booking
        if (auto) autoTried += visit.requestKey(vehicleId)
        return result
    }

    /** Advances sample provider answers. Returns true when something changed. */
    fun tick(now: Long): Boolean {
        var changed = false
        bookings.replaceAll { b -> BookingFlow.tick(b, now).also { if (it != b) changed = true } }
        return changed
    }

    fun plans(today: LocalDate): List<ItemPlan> {
        val v = vehicle
        val tailored = v.copy(severe = effectiveSevere(today, v))
        return Planner.plan(tailored, recordsOf(v.id), currentKm(v.id), Planner.rate(readingsOf(v.id), today), today, prefsOf(v.id))
    }

    fun visitPlan(plans: List<ItemPlan>, today: LocalDate, now: LocalDateTime): VisitPlan {
        val booked = activeBooking()?.ruleIds?.toSet().orEmpty()
        return Planner.visits(plans, booked, SampleData.shops, prefsOf(), today, now, profile(today).quietBlocks)
    }
}
