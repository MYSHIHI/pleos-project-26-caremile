package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** JSON document for [CareState]. Kept free of Android types so it can be tested on the JVM. */
object CareJson {
    const val VERSION = 1

    private fun <T> JSONArray?.list(map: (JSONObject) -> T?): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(map) }
    private fun JSONObject.str(key: String) = optString(key).ifEmpty { null }
    private fun JSONObject.date(key: String) = str(key)?.let(LocalDate::parse)
    private fun ints(a: JSONArray?) = if (a == null) emptySet() else (0 until a.length()).map { a.optInt(it) }.toSet()
    private fun strs(a: JSONArray?) = if (a == null) emptySet() else (0 until a.length()).map { a.optString(it) }.toSet()

    fun write(s: CareState): String = JSONObject()
        .put("version", VERSION).put("vehicleId", s.vehicleId).put("sampleMode", s.sampleMode).put("dayOffset", s.dayOffset)
        .put("scenario", s.scenario.name).put("pattern", s.pattern.name).put("seq", s.seq).put("autoTried", JSONArray(s.autoTried.toList()))
        .put("vehicles", JSONArray(s.vehicles.map { v ->
            JSONObject().put("id", v.id).put("name", v.name).put("model", v.model).put("year", v.year).put("powertrain", v.powertrain)
                .put("delivery", v.deliveryDate?.toString() ?: "").put("sample", v.sample).put("confirmed", v.profileConfirmed)
                .put("severe", JSONArray(v.severe.sorted())).put("excluded", JSONArray(v.excluded.sorted()))
        }))
        .put("records", JSONArray(s.records.map { r ->
            JSONObject().put("id", r.id).put("vehicle", r.vehicleId).put("item", r.itemId).put("action", r.action.name)
                .put("date", r.date.toString()).put("km", r.km).put("source", r.source.name).put("fromNew", r.fromNew)
                .put("booking", r.bookingId ?: "").put("edited", r.edited)
        }))
        .put("readings", JSONObject().apply {
            s.readings.forEach { (id, list) -> put(id, JSONArray(list.map {
                JSONObject().put("km", it.km).put("at", it.atMillis).put("date", it.date.toString()).put("source", it.source.name)
            })) }
        })
        .put("prefs", JSONObject().apply {
            s.prefs.forEach { (id, p) -> put(id, JSONObject().put("blocks", JSONArray(p.blocks.map { it.name })).put("budget", p.budget)
                .put("shops", JSONArray(p.preferredShops.toList())).put("auto", p.auto.enabled).put("autoMax", p.auto.maxCost)) }
        })
        .put("trips", JSONArray(s.trips.map { t ->
            JSONObject().put("id", t.id).put("vehicle", t.vehicleId).put("start", t.startMillis).put("end", t.endMillis)
                .put("date", t.date.toString()).put("hour", t.startHour).put("km", t.km).put("min", t.minutes)
                .put("idle", t.idleShare ?: JSONObject.NULL).put("high", t.highSpeedShare ?: JSONObject.NULL)
                .put("hard", t.hardEvents ?: JSONObject.NULL).put("brakes", t.brakePresses ?: JSONObject.NULL)
                .put("temp", t.outsideC ?: JSONObject.NULL).put("source", t.source.name)
        }))
        .put("bookings", JSONArray(s.bookings.map { b ->
            JSONObject().put("id", b.id).put("key", b.requestKey).put("vehicle", b.vehicleId).put("shop", b.shopId).put("shopName", b.shopName)
                .put("at", b.at.toString()).put("rules", JSONArray(b.ruleIds)).put("cost", b.confirmedCost).put("unpriced", b.unpriced)
                .put("auto", b.auto).put("scenario", b.scenario.name).put("state", b.state.name).put("updated", b.updatedAt)
                .put("changeTo", b.changeTo?.toString() ?: "")
                .put("events", JSONArray(b.events.map { JSONObject().put("at", it.atMillis).put("text", it.text) }))
        }))
        .toString()

    /** Returns false (and leaves [s] untouched) for missing, unknown or damaged data. */
    fun read(text: String?, s: CareState): Boolean = try {
        val d = JSONObject(text ?: "{}")
        if (d.optInt("version") != VERSION) false else {
            val vehicles = d.optJSONArray("vehicles").list { v ->
                Vehicle(v.getString("id"), v.optString("name"), v.optString("model"), v.optInt("year"), v.optString("powertrain"),
                    v.date("delivery"), v.optBoolean("sample"), v.optBoolean("confirmed"), ints(v.optJSONArray("severe")), ints(v.optJSONArray("excluded")))
            }
            require(vehicles.isNotEmpty() && vehicles.any { it.id == d.optString("vehicleId") })
            val records = d.optJSONArray("records").list { r ->
                Record(r.getString("id"), r.getString("vehicle"), r.getString("item"), Action.valueOf(r.getString("action")),
                    LocalDate.parse(r.getString("date")), r.getInt("km"), Source.valueOf(r.getString("source")),
                    r.optBoolean("fromNew"), r.str("booking"), r.optBoolean("edited"))
            }
            val readings = mutableMapOf<String, MutableList<Reading>>()
            d.optJSONObject("readings")?.let { o -> o.keys().forEach { id ->
                readings[id] = o.optJSONArray(id).list { Reading(it.getDouble("km"), it.getLong("at"), LocalDate.parse(it.getString("date")),
                    Source.valueOf(it.getString("source"))) }.toMutableList()
            } }
            val prefs = mutableMapOf<String, Preferences>()
            d.optJSONObject("prefs")?.let { o -> o.keys().forEach { id ->
                val p = o.getJSONObject(id)
                prefs[id] = Preferences(strs(p.optJSONArray("blocks")).mapNotNull { n -> TimeBlock.entries.find { it.name == n } }.toSet(),
                    p.optInt("budget", 200_000), strs(p.optJSONArray("shops")), AutoBooking(p.optBoolean("auto"), p.optInt("autoMax", 150_000)))
            } }
            fun JSONObject.num(key: String): Double? = if (isNull(key) || !has(key)) null else getDouble(key)
            val trips = d.optJSONArray("trips").list { t ->
                Trip(t.getString("id"), t.getString("vehicle"), t.getLong("start"), t.getLong("end"), LocalDate.parse(t.getString("date")),
                    t.optInt("hour"), t.getDouble("km"), t.getDouble("min"), t.num("idle"), t.num("high"), t.num("hard")?.toInt(),
                    t.num("brakes")?.toInt(), t.num("temp"), Source.valueOf(t.getString("source")))
            }
            val bookings = d.optJSONArray("bookings").list { b ->
                val rules = b.optJSONArray("rules")
                Booking(b.getString("id"), b.getString("key"), b.getString("vehicle"), b.getString("shop"), b.optString("shopName"),
                    LocalDateTime.parse(b.getString("at")), (0 until (rules?.length() ?: 0)).map { rules!!.getString(it) },
                    b.optInt("cost"), b.optInt("unpriced"), b.optBoolean("auto"), Scenario.valueOf(b.getString("scenario")),
                    BookingState.valueOf(b.getString("state")), b.optLong("updated"),
                    b.optJSONArray("events").list { Event(it.optLong("at"), it.optString("text")) },
                    b.str("changeTo")?.let(LocalDateTime::parse))
            }
            s.vehicles.clear(); s.vehicles += vehicles
            s.records.clear(); s.records += records
            s.readings.clear(); s.readings += readings
            s.prefs.clear(); s.prefs += prefs
            s.bookings.clear(); s.bookings += bookings
            s.trips.clear(); s.trips += trips
            s.pattern = DrivePattern.entries.find { it.name == d.optString("pattern") } ?: DrivePattern.COMMUTE
            s.autoTried.clear(); s.autoTried += strs(d.optJSONArray("autoTried"))
            s.vehicleId = d.getString("vehicleId")
            s.sampleMode = d.optBoolean("sampleMode", true)
            s.dayOffset = d.optInt("dayOffset").coerceIn(0, 3650)
            s.scenario = Scenario.entries.find { it.name == d.optString("scenario") } ?: Scenario.WAIT
            s.seq = d.optLong("seq")
            true
        }
    } catch (_: Exception) { false }
}
