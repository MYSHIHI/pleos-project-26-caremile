package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit.DAYS
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val base = Color.rgb(14, 23, 25)
    private val panel = Color.rgb(24, 37, 40)
    private val panel2 = Color.rgb(31, 48, 52)
    private val line = Color.rgb(48, 69, 74)
    private val ink = Color.rgb(234, 242, 239)
    private val muted = Color.rgb(157, 176, 172)
    private val mint = Color.rgb(127, 224, 194)
    private val amber = Color.rgb(242, 184, 91)
    private val coral = Color.rgb(242, 128, 107)
    private val sky = Color.rgb(140, 200, 242)

    private enum class Tab(val label: String) {
        HOME("관리 현황"), DRIVING("운행 이력"), PLAN("정비 계획"), BOOKING("방문·예약"), RECORDS("정비 기록"), CONDITIONS("내 조건"), SAMPLE("샘플·연결")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val state = CareState()
    private lateinit var store: SharedPreferences
    private lateinit var inputs: VehicleInputs
    private lateinit var guard: DrivingGuard
    private var tab = Tab.HOME
    private var started = false
    private var uxAllowed = false
    private var uxMessage = "정차 상태 확인 중"
    private var odo = OdoSignal()
    private var trip = TripSignal()
    private var realTires: Map<String, TireSignal> = emptyMap()
    private var sampleTireLow = false
    private var sampleFluidLow = false
    private var builder: TripBuilder? = null
    private var gear = "-"
    private var speedKph: Double? = null
    private var outsideC: Double? = null
    private var brakeFluid: String? = null
    private val signals = linkedMapOf<String, String>()
    private var lastLiveRender = 0L
    private var inputsRunning = false
    private var finishing = false
    private var askedSpeed = false
    private var message: String? = null
    private var messageAt = 0L
    private var dialog: AlertDialog? = null
    private var scroll: ScrollView? = null
    private val scrollY = mutableMapOf<Tab, Int>()
    private var renderPending = false
    private val allowed get() = started && uxAllowed
    private val day = 86_400_000L
    private val dateFmt = DateTimeFormatter.ofPattern("M월 d일(E)", Locale.KOREAN)
    private val timeFmt = DateTimeFormatter.ofPattern("M월 d일(E) HH:mm", Locale.KOREAN)

    private fun offset() = if (state.sampleMode) state.dayOffset else 0
    private fun today(): LocalDate = LocalDate.now().plusDays(offset().toLong())
    private fun now(): LocalDateTime = LocalDateTime.now().plusDays(offset().toLong())
    private fun clockMillis() = System.currentTimeMillis() + offset() * day

    private companion object { const val SPEED = "android.car.permission.CAR_SPEED" }

    private val ticker = object : Runnable {
        override fun run() {
            if (!started) return
            if (state.tick(System.currentTimeMillis())) { save(); maybeAutoBook(); requestRender() }
            if (message != null && System.currentTimeMillis() - messageAt > 7_000) { message = null; requestRender() }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = getSharedPreferences("care_mile_v1", MODE_PRIVATE)
        if (!CareJson.read(store.getString("state", null), state)) {
            SampleData.seed(state, LocalDate.now(), System.currentTimeMillis())
            save()
        }
        inputs = VehicleInputs(this, { signal ->
            odo = signal
            val km = signal.km
            if (km != null && !state.sampleMode) {
                builder?.odometer(km.toDouble())
                when (state.addReading(SampleData.REAL, km.toDouble(), Source.VEHICLE, signal.atMillis, today())) {
                    ReadingResult.DECREASE -> notify("주행거리가 이전보다 작게 들어왔어요 · 정비 기록이나 주행거리에 반영하지 않았어요")
                    ReadingResult.ACCEPTED -> { save(); maybeAutoBook() }
                    ReadingResult.UNCHANGED -> save()
                }
            }
            requestRender()
        }, { trip = it; requestRender() }, { realTires = it; requestRender() }, ::onDrive)
        guard = DrivingGuard(this) { ok, info ->
            if (!started) return@DrivingGuard
            val changed = ok != uxAllowed
            uxAllowed = ok; uxMessage = info
            if (!ok) { dialog?.dismiss(); dialog = null }
            if (changed || !ok) requestRender()
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true; uxAllowed = false
        guard.start()
        if (!state.sampleMode) startRealInputs()
        maybeAutoBook()
        handler.post(ticker)
        render()
    }

    override fun onStop() {
        started = false
        save()
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss(); dialog = null
        // Real-vehicle inputs keep running in the background so the current drive is still recorded.
        if (state.sampleMode) { inputs.stop(); inputsRunning = false }
        guard.stop()
        uxAllowed = false
        super.onStop()
    }

    override fun onDestroy() { inputs.stop(); inputsRunning = false; super.onDestroy() }

    private fun startRealInputs() {
        if (checkSelfPermission(SPEED) != android.content.pm.PackageManager.PERMISSION_GRANTED && !askedSpeed) {
            askedSpeed = true
            requestPermissions(arrayOf(SPEED), 7)
        }
        if (!inputsRunning) { inputs.start(); inputsRunning = true }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && !state.sampleMode) { inputs.start(); inputsRunning = true }
    }

    /** Real-vehicle driving signals: leaving Park starts a drive, returning to Park ends and records it. */
    private fun onDrive(e: DriveEvent) {
        if (state.sampleMode) return
        when (e) {
            is DriveEvent.Gear -> {
                gear = e.label
                val b = builder
                if (!e.park && b == null) {
                    builder = TripBuilder(System.currentTimeMillis(), odo.km?.toDouble() ?: state.lastReading(SampleData.REAL)?.km, outsideC)
                    notify("운행을 시작했어요 · 주행거리·속도·브레이크 조작을 운행 이력으로 기록해요")
                } else if (e.park && b != null && !finishing) {
                    // Wait briefly for the final odometer value before closing the drive.
                    finishing = true
                    inputs.refreshOdometer()
                    handler.postDelayed({ finishing = false; if (gear == "P" && builder === b) finishTrip(b) }, 3_000)
                }
                requestRender()
            }
            is DriveEvent.Speed -> { speedKph = e.kph; builder?.speed(e.atMillis, e.kph); liveRender() }
            is DriveEvent.Brake -> builder?.brake(e.pressed)
            is DriveEvent.Temp -> { outsideC = e.celsius; builder?.temp(e.celsius) }
            is DriveEvent.BrakeFluid -> { brakeFluid = e.state; requestRender() }
            is DriveEvent.Status -> { signals[e.key] = e.text; requestRender() }
        }
    }

    private fun liveRender() {
        val t = System.currentTimeMillis()
        if (t - lastLiveRender > 1_000 && (tab == Tab.SAMPLE || tab == Tab.DRIVING || !allowed)) { lastLiveRender = t; requestRender() }
    }

    private fun finishTrip(b: TripBuilder) {
        builder = null
        odo.km?.let { b.odometer(it.toDouble()) }
        val before = state.effectiveSevere(today())
        val t = b.finish(System.currentTimeMillis(), state.nextId("t"), SampleData.REAL)
        if (t == null) { notify("아주 짧은 이동이라 운행 이력에 넣지 않았어요"); return }
        afterTrip(t, before)
    }

    /** After a drive: store it, re-run the tailoring and tell the user what changed at the moment the car is parked. */
    private fun afterTrip(t: Trip, before: Set<Int>) {
        state.addTrip(t)
        save(); maybeAutoBook()
        val after = state.effectiveSevere(today())
        val next = state.plans(today()).firstOrNull { it.status == Status.OVERDUE || it.status == Status.SOON }
        val added = (after - before).sorted()
        val removed = (before - after).sorted()
        val change = when {
            added.isNotEmpty() -> " · 운행 이력으로 가혹 조건 ${added.joinToString(",")}번이 새로 적용돼 정비 주기를 다시 맞췄어요"
            removed.isNotEmpty() -> " · 가혹 조건 ${removed.joinToString(",")}번이 더 이상 해당하지 않아 통상 주기로 돌렸어요"
            else -> ""
        }
        notify("운행 종료: ${"%.1f".format(t.km)} km · ${t.minutes.roundToInt()}분 기록$change" + (next?.let { " · 다음 정비: ${it.rule.title} (${shortDue(it)})" } ?: ""))
    }

    private fun save() { store.edit().putString("state", CareJson.write(state)).commit() }

    private fun notify(text: String) { message = text; messageAt = System.currentTimeMillis(); requestRender() }

    private fun requestRender() {
        if (renderPending) return
        renderPending = true
        handler.postDelayed({ render() }, 120)
    }

    private fun urgent(): List<String> = buildList {
        if (state.sampleMode) {
            if (state.vehicleId == SampleData.A && sampleTireLow) add("타이어 공기압이 낮아요 · 앞 왼쪽 (샘플)")
            if (state.vehicleId == SampleData.A && sampleFluidLow) add("브레이크 오일 레벨이 부족해요 (샘플)")
        } else {
            realTires.values.filter { it.warning }.forEach { add("타이어 공기압이 낮아요 · ${it.wheel} (차량 신호)") }
            brakeFluid?.takeIf { it == "부족" || it == "매우 부족" }?.let { add("브레이크 오일 레벨이 $it (차량 신호)") }
        }
    }

    /** Requests the earliest planned visit by itself only when every condition the user allowed is met. */
    private fun maybeAutoBook() {
        if (!started) return
        val t = today()
        val visit = state.visitPlan(state.plans(t), t, now()).visits.firstOrNull() ?: return
        val key = visit.requestKey(state.vehicleId)
        if (key in state.autoTried) return
        val prefs = state.prefsOf()
        if (BookingFlow.autoBlockers(visit, prefs, state.odometerFresh(t), state.activeBooking() != null).isNotEmpty()) return
        state.request(visit, auto = true, now = System.currentTimeMillis())
        save()
        notify("허용한 조건 안이라 ${visit.shop.name} ${visit.slot.at.format(timeFmt)} 예약을 자동으로 요청했어요 (샘플)")
    }

    // ---------- view helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun rounded(fill: Int, radius: Int = 16, border: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (border != null) setStroke(dp(1), border)
    }
    private fun text(value: String, size: Float = 17f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(2).toFloat(), 1.05f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun button(value: String, primary: Boolean = false, enabled: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 16f
        minHeight = dp(50); minimumHeight = dp(50); minWidth = dp(64); minimumWidth = dp(64)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        setTextColor(if (primary) base else ink)
        background = rounded(if (primary) mint else panel2, 12, if (primary) null else line)
        isEnabled = enabled; alpha = if (enabled) 1f else .4f
        setOnClickListener { if (allowed) action() }
    }
    private fun chip(value: String, color: Int) = text(value, 14f, color, true).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4)); background = rounded(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), 20)
    }
    private fun toggle(value: String, on: Boolean, action: () -> Unit) = button(if (on) "✓ $value" else value, action = action).apply {
        if (on) { background = rounded(Color.argb(60, 127, 224, 194), 12, mint); setTextColor(mint) }
    }
    private fun gap(h: Int = 12) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun LinearLayout.add(v: View, top: Int = 0, weight: Float = 0f, width: Int = LinearLayout.LayoutParams.WRAP_CONTENT): View {
        val lp = if (orientation == LinearLayout.VERTICAL)
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        else LinearLayout.LayoutParams(if (weight > 0f) 0 else width, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        lp.topMargin = dp(top)
        if (orientation == LinearLayout.HORIZONTAL && childCount > 0) lp.marginStart = dp(10)
        addView(v, lp); return v
    }
    private fun card(fill: Int = panel, border: Int? = null, build: LinearLayout.() -> Unit) = column().apply {
        background = rounded(fill, 18, border); setPadding(dp(20), dp(16), dp(20), dp(18)); build()
    }
    private fun buttons(vararg views: View) = row().apply { views.forEach { add(it) } }
    private fun grid(views: List<View>, perRow: Int) = column().apply {
        views.chunked(perRow).forEachIndexed { i, chunk -> add(row().apply { chunk.forEach { add(it, weight = 1f) }; repeat(perRow - chunk.size) { add(View(context), weight = 1f) } }, top = if (i == 0) 0 else 8) }
    }
    private fun statusColor(s: Status) = when (s) {
        Status.OVERDUE -> coral; Status.SOON -> amber; Status.NEEDS_HISTORY, Status.NEEDS_ODOMETER -> sky; Status.OK -> mint; else -> muted
    }
    private fun bookingColor(s: BookingState) = when (s) {
        BookingState.CONFIRMED, BookingState.COMPLETED -> mint; BookingState.REJECTED, BookingState.EXPIRED, BookingState.UNKNOWN -> coral
        BookingState.CANCELLED -> muted; else -> amber
    }
    private fun header(title: String, sub: String) = column().apply {
        add(text(title, 28f, ink, true)); add(text(sub, 16f, muted), top = 4)
    }

    // ---------- render ----------
    private fun render() {
        renderPending = false
        scroll?.let { scrollY[tab] = it.scrollY }
        val root = row().apply { gravity = Gravity.TOP; setBackgroundColor(base); fitsSystemWindows = true }
        root.addView(rail(), LinearLayout.LayoutParams(dp(236), LinearLayout.LayoutParams.MATCH_PARENT))
        val content = column().apply { setPadding(dp(28), dp(22), dp(28), dp(40)) }
        message?.let { content.add(card(Color.argb(50, 127, 224, 194), mint) { add(text(it, 16f, ink)) }); content.add(gap(14)) }
        if (!allowed) glance(content) else when (tab) {
            Tab.HOME -> home(content); Tab.DRIVING -> driving(content); Tab.PLAN -> plan(content); Tab.BOOKING -> booking(content)
            Tab.RECORDS -> records(content); Tab.CONDITIONS -> conditions(content); Tab.SAMPLE -> sample(content)
        }
        val sv = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(sv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        scroll = sv
        setContentView(root)
        val y = scrollY[tab] ?: 0
        sv.post { sv.scrollTo(0, y) }
    }

    private fun rail() = column().apply {
        setBackgroundColor(panel); setPadding(dp(16), dp(22), dp(16), dp(18))
        add(text("케어마일", 24f, mint, true)); add(text("CARE MILE · 정비 스케줄러", 13f, muted), top = 2)
        val v = state.vehicle
        add(card(panel2) {
            setPadding(dp(14), dp(10), dp(14), dp(12))
            add(text(v.name, 16f, ink, true)); add(text(if (v.sample) "샘플 차량 · 가상 데이터" else "실차 입력", 13f, if (v.sample) amber else sky), top = 2)
        }, top = 18)
        Tab.entries.forEachIndexed { i, t ->
            add(Button(context).apply {
                text = "%02d  %s".format(i + 1, t.label); isAllCaps = false; textSize = 16f; gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minHeight = dp(52); minimumHeight = dp(52); setPadding(dp(14), 0, dp(10), 0)
                setTextColor(if (t == tab) base else ink); background = rounded(if (t == tab) mint else panel, 12)
                isEnabled = allowed; alpha = if (allowed) 1f else .45f
                setOnClickListener { if (allowed) { tab = t; render() } }
            }, top = if (i == 0) 16 else 6)
        }
        add(View(context), weight = 1f)
        val t = today()
        add(text((if (state.sampleMode) "샘플 날짜 " else "오늘 ") + t.format(dateFmt) + if (offset() > 0) " (+${offset()}일)" else "", 13f, muted), top = 14)
        add(text(uxMessage, 13f, if (allowed) mint else amber), top = 4)
    }

    private fun glance(c: LinearLayout) {
        val t = today()
        val plans = state.plans(t)
        c.add(header("주행 중에는 확인만 할 수 있어요", "정차하면 계획 확인·예약·기록 입력을 이어갈 수 있어요"))
        c.add(card {
            add(text("현재 주행거리", 15f, muted)); add(text(state.currentKm()?.let { "${fmtKm(it)} km" } ?: "확인 필요", 40f, ink, true), top = 2)
        }, top = 18)
        builder?.let { b -> c.add(card(panel, sky) {
            add(text("운행 기록 중 · ${"%.1f".format(b.distanceKm())} km · ${((System.currentTimeMillis() - b.startMillis) / 60_000)}분", 20f, sky, true))
            add(text("기어를 P에 두면 이번 운행을 이력에 넣고 맞춤 정비 계획을 다시 계산해요", 15f, muted), top = 4)
        }, top = 12) }
        urgent().takeIf { it.isNotEmpty() }?.let { c.add(urgentCard(it), top = 12) }
        plans.filter { it.status == Status.OVERDUE || it.status == Status.SOON }.take(3).forEach { p ->
            c.add(card { add(row().apply {
                add(text(p.rule.title, 21f, ink, true), weight = 1f); add(chip(p.status.label, statusColor(p.status)))
            }); add(text(shortDue(p), 17f, muted), top = 4) }, top = 12)
        }
        state.activeBooking()?.let { b -> c.add(card { add(text("${b.shopName} · ${b.at.format(timeFmt)}", 19f, ink, true)); add(text("샘플 예약 · ${b.state.label}", 16f, bookingColor(b.state)), top = 4) }, top = 12) }
    }

    private fun shortDue(p: ItemPlan) = listOfNotNull(
        p.remainingKm?.let { if (it > 0) "${fmtKm(it)} km 남음" else "${fmtKm(-it)} km 지남" },
        p.remainingDays?.let { if (it > 0) "${it}일 남음" else "${-it}일 지남" },
        p.projectedKmDate?.takeIf { p.remainingKm != null && p.remainingKm > 0 }?.let { "예상 ${it.format(dateFmt)}" },
    ).joinToString(" · ").ifEmpty { p.status.label }

    // ---------- 01 관리 현황 ----------
    private fun home(c: LinearLayout) {
        val t = today()
        val v = state.vehicle
        val plans = state.plans(t)
        val rate = Planner.rate(state.readingsOf(), t)
        c.add(header("관리 현황", "${v.name} · ${v.model} ${v.year} ${v.powertrain}"))
        val last = state.lastReading()
        c.add(row().apply {
            gravity = Gravity.TOP
            add(card {
                add(text("현재 주행거리", 15f, muted))
                add(text(state.currentKm()?.let { "${fmtKm(it)} km" } ?: "확인 필요", 38f, ink, true), top = 2)
                add(text(last?.let { "${it.source.label} · ${it.date.format(dateFmt)} 확인" } ?: "주행거리 기록 없음", 14f, if (last?.source == Source.VEHICLE) sky else muted), top = 4)
                add(text(rate.text, 14f, muted), top = 2)
            }, weight = 1f)
            val next = plans.filter { it.status == Status.OVERDUE || it.status == Status.SOON }
            add(card {
                add(text("다음 정비", 15f, muted))
                val first = next.firstOrNull()
                add(text(first?.rule?.title ?: "가까운 정비 없음", 24f, ink, true), top = 4)
                add(text(first?.let { shortDue(it) } ?: "기준표에 따라 여유가 있어요", 15f, first?.let { statusColor(it.status) } ?: mint), top = 4)
                if (next.size > 1) add(text("함께 볼 항목 ${next.size - 1}개 더 · 정비 계획에서 묶어 볼 수 있어요", 14f, muted), top = 6)
            }, weight = 1.3f)
        }, top = 18)
        if (!RuleBook.applies(v)) {
            c.add(card(panel, sky) {
                if (v.model == RuleBook.MODEL && !v.profileConfirmed) {
                    add(text("차종 확인이 필요해요", 20f, sky, true))
                    add(text("${RuleBook.MODEL} ${RuleBook.YEAR} 기준표를 적용하려면 이 차량이 그 차종이 맞는지 직접 확인해 주세요. 확인 전에는 일정을 계산하지 않아요.", 15f, muted), top = 6)
                    add(buttons(button("${RuleBook.MODEL} ${RuleBook.YEAR}이 맞아요", primary = true) {
                        state.updateVehicle(v.copy(profileConfirmed = true)); save(); render()
                    }), top = 12)
                } else {
                    add(text("이 차종의 공식 기준표가 아직 없어요", 20f, sky, true))
                    add(text("${v.model} ${v.year}에는 정비 일정을 계산하지 않아요. 다른 차종의 주기를 임의로 적용하지 않습니다.", 15f, muted), top = 6)
                }
            }, top = 14)
            return
        }
        c.add(tailoredCard(t), top = 14)
        urgent().takeIf { it.isNotEmpty() }?.let { c.add(urgentCard(it), top = 12) }
        c.add(row().apply {
            listOf(Status.OVERDUE, Status.SOON, Status.NEEDS_HISTORY, Status.OK).forEach { s ->
                add(chip("${s.label} ${plans.count { it.status == s }}", statusColor(s)))
            }
        }, top = 14)
        plans.forEach { c.add(itemCard(it), top = 12) }
    }

    private fun urgentCard(items: List<String>) = card(Color.argb(45, 242, 128, 107), coral) {
        add(text("즉시 확인 · 묶음 예약을 기다리지 마세요", 19f, coral, true))
        items.forEach { add(text("• $it", 16f, ink), top = 6) }
        add(text("가까운 곳에서 먼저 점검하세요. 공기압은 타이어 마모를, 오일 레벨 경고는 교체 주기를 뜻하지 않아요.", 14f, muted), top = 6)
    }

    private fun conditionName(n: Int) = RuleBook.severeConditions.getOrNull(n - 1) ?: "조건 $n"

    /** How the plan is tailored to this car: manufacturer table + conditions from driving history and the user. */
    private fun tailoredCard(t: LocalDate) = card(panel, mint) {
        val v = state.vehicle
        val profile = state.profile(t)
        val fromDriving = profile.detected - v.excluded
        val applied = state.effectiveSevere(t)
        add(row().apply {
            add(text("내 차 맞춤 정비 기준", 19f, mint, true), weight = 1f)
            add(chip(if (applied.isEmpty()) "통상 조건" else "가혹 조건 ${applied.sorted().joinToString(",")}번", if (applied.isEmpty()) mint else amber))
        })
        add(text("${RuleBook.TITLE} · ${RuleBook.CHECKED}", 14f, muted), top = 4)
        add(text(if (profile.enough) "운행 이력: 최근 ${DrivingCriteria.WINDOW_DAYS}일 ${profile.trips.size}회 · ${fmtKm(profile.totalKm.roundToInt())} km · 평균 ${"%.1f".format(profile.avgTripKm)} km/회 · ${"%.0f".format(profile.avgKph)} km/h"
            else "운행 이력 ${profile.trips.size}회 · ${DrivingCriteria.MIN_TRIPS}회 이상 쌓이면 운행 맞춤 판정을 시작해요", 15f, ink), top = 6)
        fromDriving.sorted().forEach { n -> profile.findings.find { it.condition == n }?.let { f ->
            add(text("운행 이력 → $n. ${conditionName(n)} · ${f.measured}", 15f, amber), top = 4)
        } }
        v.severe.sorted().forEach { n -> add(text("직접 선택 → $n. ${conditionName(n)}", 15f, sky), top = 4) }
        DrivingAnalyzer.changes(applied).filter { it.first.severe?.interval != null }.take(4).forEach { (rule, text) ->
            add(text("• ${rule.title}: $text", 15f, ink), top = 4)
        }
        add(buttons(button("운행 이력·판정 근거 보기") { tab = Tab.DRIVING; render() }), top = 10)
    }

    // ---------- 02 운행 이력 ----------
    private fun driving(c: LinearLayout) {
        val t = today()
        val v = state.vehicle
        val profile = state.profile(t)
        c.add(header("운행 이력", "실제 운행 패턴으로 취급설명서의 가혹 조건 해당 여부를 판정해 정비 주기를 맞춰요"))
        if (state.sampleMode) c.add(card(panel2) {
            add(text("샘플 운행 패턴 (가상 운행 기록 30일)", 17f, ink, true))
            add(grid(DrivePattern.entries.map { p -> toggle("${p.label}\n${p.note}", state.pattern == p) { setPattern(p) }.apply { textSize = 14f } }, 4), top = 10)
            add(buttons(button("운행 1회 추가") { sampleTrip() }, button("+7일 운행") { advance(7) }), top = 10)
        }, top = 14)
        else c.add(liveCard(), top = 14)
        c.add(row().apply {
            gravity = Gravity.TOP
            listOf(
                "운행" to "${profile.trips.size}회",
                "거리" to "${fmtKm(profile.totalKm.roundToInt())} km",
                "평균 운행" to "${"%.1f".format(profile.avgTripKm)} km",
                "평균 속도" to "${"%.0f".format(profile.avgKph)} km/h",
            ).forEach { (k, value) -> add(card { add(text(k, 14f, muted)); add(text(value, 24f, ink, true), top = 2) }, weight = 1f) }
        }, top = 12)
        c.add(card {
            add(text("가혹 조건 판정 (최근 ${DrivingCriteria.WINDOW_DAYS}일)", 18f, ink, true))
            add(text("판정 기준은 앱 초안이에요(취급설명서에 수치 정의가 없음, 정비 전문가 검토 예정). 해당하면 취급설명서의 가혹 조건 주기만 적용해요.", 14f, muted), top = 4)
            profile.findings.forEach { f ->
                val excluded = f.condition in v.excluded
                add(row().apply {
                    gravity = Gravity.TOP
                    add(column().apply {
                        add(text("${f.condition}. ${conditionName(f.condition)}", 16f, ink, true))
                        add(text("측정: ${f.measured}", 14f, if (f.state == FindingState.DETECTED) amber else muted), top = 2)
                        add(text("앱 기준: ${f.criterion}", 13f, muted), top = 2)
                    }, weight = 1f)
                    add(chip(if (excluded && f.state == FindingState.DETECTED) "제외함" else f.state.label,
                        when { excluded -> muted; f.state == FindingState.DETECTED -> amber; f.state == FindingState.CLEAR -> mint; else -> sky }))
                    if (f.state == FindingState.DETECTED) add(button(if (excluded) "다시 적용" else "제외") {
                        state.updateVehicle(v.copy(excluded = if (excluded) v.excluded - f.condition else v.excluded + f.condition)); save(); maybeAutoBook(); render()
                    })
                }, top = 12)
            }
            add(text("모래·험로·산길·영업용·견인·오일 종류(2,5,6,7,11,12번)는 차량 신호로 알 수 없어 내 조건에서 직접 골라요.", 14f, muted), top = 12)
        }, top = 12)
        val applied = state.effectiveSevere(t)
        c.add(card(panel, if (applied.isEmpty()) null else amber) {
            add(text("이 판정으로 바뀐 정비", 18f, ink, true))
            val changes = DrivingAnalyzer.changes(applied)
            if (changes.isEmpty()) add(text("모든 항목을 통상 조건 주기로 관리해요.", 15f, mint), top = 6)
            changes.forEach { (rule, text) -> add(text("• ${rule.title}: $text", 15f, if (rule.severe?.interval != null) amber else muted), top = 6) }
        }, top = 12)
        c.add(card {
            add(text("주차 패턴 · 정비 방문하기 좋은 시간", 18f, ink, true))
            if (!profile.enough) add(text("운행 이력이 더 쌓이면 차를 주로 쓰지 않는 시간을 찾아드려요.", 15f, muted), top = 6)
            else {
                add(grid(TimeBlock.entries.map { b ->
                    val u = profile.usage[b] ?: 0.0
                    column().apply {
                        background = rounded(if (b in profile.quietBlocks) Color.argb(50, 127, 224, 194) else panel2, 12)
                        setPadding(dp(12), dp(8), dp(12), dp(10))
                        add(text(b.label, 15f, ink, true)); add(text("운행한 날 ${(u * 100).roundToInt()}%", 14f, if (b in profile.quietBlocks) mint else muted), top = 2)
                    }
                }, 5), top = 10)
                val quiet = profile.quietBlocks
                add(text(if (quiet.isEmpty()) "자주 비는 시간대가 없어요." else "차를 주로 쓰지 않는 시간: ${quiet.joinToString { it.label }} · 정비 계획에서 이 시간의 방문을 우선 추천해요", 15f, mint), top = 10)
                val prefs = state.prefsOf()
                if (quiet.isNotEmpty() && !prefs.blocks.containsAll(quiet)) add(buttons(button("이 시간대를 방문 가능 시간에 추가") {
                    state.updatePrefs(prefs.copy(blocks = prefs.blocks + quiet)); save(); maybeAutoBook(); render()
                }), top = 8)
            }
        }, top = 12)
        c.add(card {
            add(text("최근 운행", 18f, ink, true))
            val fmt = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.KOREAN)
            profile.trips.sortedByDescending { it.startMillis }.take(8).forEach { trip ->
                val tags = buildList {
                    if (trip.km <= DrivingCriteria.SHORT_TRIP_KM) add("단거리")
                    if (trip.avgKph <= DrivingCriteria.CONGESTED_KPH) add("저속")
                    if ((trip.highSpeedShare ?: 0.0) >= DrivingCriteria.HIGH_SPEED_SHARE) add("고속")
                    if ((trip.idleShare ?: 0.0) >= DrivingCriteria.IDLE_SHARE) add("정차 많음")
                }
                val start = java.time.Instant.ofEpochMilli(trip.startMillis).atZone(java.time.ZoneId.systemDefault())
                add(text("${start.format(fmt)} · ${"%.1f".format(trip.km)} km · ${trip.minutes.roundToInt()}분 · ${"%.0f".format(trip.avgKph)} km/h" +
                    (trip.brakePresses?.let { " · 브레이크 ${it}회" } ?: "") + (if (tags.isEmpty()) "" else "  [${tags.joinToString()}]") +
                    " · ${trip.source.label}", 14f, muted), top = 4)
            }
            if (profile.trips.isEmpty()) add(text("아직 기록된 운행이 없어요.", 15f, muted), top = 4)
        }, top = 12)
    }

    private fun liveCard() = card(panel, if (builder != null) sky else null) {
        add(text("실시간 차량 신호", 18f, ink, true))
        add(text("기어 $gear · 속도 ${speedKph?.let { "%.0f km/h".format(it) } ?: "-"} · 외기 ${outsideC?.let { "%.0f℃".format(it) } ?: "-"} · 누적 ${odo.km?.let { "%,.1f km".format(it) } ?: "-"}", 16f, ink), top = 6)
        builder?.let { b -> add(text("운행 기록 중: ${"%.1f".format(b.distanceKm())} km · ${(System.currentTimeMillis() - b.startMillis) / 60_000}분 · 브레이크 ${b.brakePresses}회 · 급가감속 ${b.hardEvents}회", 16f, sky, true), top = 6) }
            ?: add(text("기어를 P에서 바꾸면 운행 기록을 시작하고, P로 돌아오면 이력에 넣어요.", 14f, muted), top = 6)
        signals.values.takeIf { it.isNotEmpty() }?.let { add(text(it.joinToString(" · "), 13f, muted), top = 6) }
    }

    private fun itemCard(p: ItemPlan) = card {
        add(row().apply {
            add(text(p.rule.title, 19f, ink, true), weight = 1f)
            if (p.severeApplied) add(chip("가혹 조건", amber))
            add(chip(p.status.label, statusColor(p.status)))
        })
        p.lines.forEach { add(text(it, 15f, if (it.startsWith("먼저 도래")) ink else muted), top = 4) }
        p.severeNote?.let { add(text(it, 15f, amber), top = 6) }
        if (p.status == Status.NEEDS_HISTORY) add(buttons(
            button("기록 입력", primary = true) { recordDetail(null, p.rule) },
            button("출고 후 안 함") {
                if (state.markFromNew(p.rule.item.id, p.rule.action)) { save(); maybeAutoBook(); notify("${p.rule.title}: 출고 후 미실시로 기록했어요 (사용자 확인)") }
                else notify("출고일을 먼저 입력해 주세요 (내 조건 → 차량 정보)")
            },
        ), top = 12)
    }

    // ---------- 02 정비 계획 ----------
    private fun plan(c: LinearLayout) {
        val t = today()
        val plans = state.plans(t)
        val prefs = state.prefsOf()
        c.add(header("정비 계획", "기한을 지키는 선에서 방문 횟수와 내 일정·예산을 맞춰요"))
        c.add(card(panel2) {
            add(text("계획 순서  ① 기한·긴급 확인  ② 정비소 취급 작업  ③ 내 시간대  ④ 확정 비용·방문 횟수", 16f, ink, true))
            add(text("기한을 넘기거나 ${prefs.advanceDays}일 넘게 앞당기지 않아요 · 기한 지난 항목은 ${prefs.overdueDays}일 안 방문만 후보 · 앞으로 ${prefs.horizonDays}일을 계획 (앱 설정)", 14f, muted), top = 6)
        }, top = 16)
        urgent().takeIf { it.isNotEmpty() }?.let { c.add(urgentCard(it), top = 12) }
        if (!RuleBook.applies(state.vehicle)) { c.add(card { add(text("이 차량은 적용할 공식 기준표가 없어 계획을 만들지 않아요.", 17f, muted)) }, top = 12); return }
        state.activeBooking()?.let { b ->
            c.add(card(panel, amber) {
                add(text("진행 중인 예약: ${b.shopName} · ${b.at.format(timeFmt)} · ${b.state.label}", 17f, amber, true))
                add(text("예약한 항목은 계획에서 빠지고, 새 예약 요청은 이 예약을 마무리한 뒤 할 수 있어요.", 14f, muted), top = 4)
                add(buttons(button("방문·예약 보기") { tab = Tab.BOOKING; render() }), top = 10)
            }, top = 12)
        }
        val vp = state.visitPlan(plans, t, now())
        if (vp.targets.isEmpty()) c.add(card { add(text("가까운 기간에 받을 정비가 없어요.", 18f, mint, true)) }, top = 12)
        vp.visits.forEachIndexed { i, visit -> c.add(visitCard(i, visit, prefs, t), top = 12) }
        if (vp.unplaced.isNotEmpty()) c.add(card(panel, sky) {
            add(text("따로 확인이 필요한 항목", 18f, sky, true))
            vp.unplaced.forEach { (p, why) -> add(text("• ${p.rule.title} (${p.status.label}) — $why", 15f, ink), top = 6) }
        }, top = 12)
        val missing = plans.count { it.status == Status.NEEDS_HISTORY }
        if (missing > 0) c.add(text("이력 확인이 필요한 ${missing}개 항목은 계획에 넣지 않았어요. 관리 현황에서 기록을 보완해 주세요.", 15f, sky), top = 14)
    }

    private fun visitCard(i: Int, visit: Visit, prefs: Preferences, t: LocalDate) = card(panel, if (i == 0) mint else null) {
        val active = state.activeBooking()
        add(row().apply {
            add(text("방문 ${i + 1} · ${visit.slot.at.format(timeFmt)}", 21f, ink, true), weight = 1f)
            add(chip("샘플 정비소", amber))
        })
        add(text("${visit.shop.name} · ${visit.shop.area} · ${blockOf(visit.slot.at).label}", 16f, muted), top = 4)
        visit.items.forEach { p ->
            val price = visit.shop.jobs.getValue(p.rule.id)
            add(text("• ${p.rule.title} — ${p.status.label}, ${Planner.latest(p, t, prefs).format(dateFmt)}까지 · ${if (price.confirmed) fmtWon(price.won) else "견적 필요"}", 16f, ink), top = 6)
        }
        add(text("확정 비용 ${fmtWon(visit.confirmedCost)}" + if (visit.unpriced.isNotEmpty()) " + 견적 필요 ${visit.unpriced.size}건" else "", 18f, ink, true), top = 10)
        add(text(listOf(
            "✓ 모든 항목 기한 안 방문",
            "${if (visit.preferred) "✓" else "·"} 선호 정비소${if (visit.preferred) "" else " 아님"}",
            "${if (visit.withinBudget) "✓" else "✗"} 예산 ${fmtWon(prefs.budget)}${if (visit.withinBudget) " 안" else " 초과"}",
            "✓ ${blockOf(visit.slot.at).label} (내 시간대)",
        ).plus(if (visit.quiet) listOf("✓ 평소 차를 쓰지 않는 시간(운행 이력)") else emptyList()).joinToString("   "), 14f, muted), top = 4)
        val blockers = BookingFlow.autoBlockers(visit, prefs, state.odometerFresh(t), active != null)
        add(text(when {
            !prefs.auto.enabled -> "자동 예약 꺼짐 · 직접 요청해 주세요"
            blockers.isEmpty() -> "자동 예약 조건 충족"
            else -> "자동 예약 보류: ${blockers.joinToString(" · ")} → 직접 확인 후 요청"
        }, 15f, if (prefs.auto.enabled && blockers.isEmpty()) mint else amber), top = 6)
        add(buttons(
            button("이 일정으로 예약 요청", primary = true, enabled = active == null) { requestVisit(visit) },
            button("다른 시간 보기", enabled = active == null) { alternatives(visit) },
        ), top = 12)
    }

    private fun requestVisit(visit: Visit) {
        when (val r = state.request(visit, auto = false, now = System.currentTimeMillis())) {
            is RequestResult.Created -> { save(); notify("${visit.shop.name}에 예약을 요청했어요 (샘플 · 외부 전송 없음)"); tab = Tab.BOOKING }
            is RequestResult.Duplicate -> notify("같은 요청이 이미 진행 중이에요 · 중복 예약을 만들지 않았어요")
            is RequestResult.Blocked -> notify(r.reason)
        }
        render()
    }

    private fun alternatives(visit: Visit) {
        val t = today()
        val options = Planner.candidates(visit.items, SampleData.shops, state.prefsOf(), t, now())
            .filter { it.slot != visit.slot }.take(8)
        if (options.isEmpty()) { notify("같은 항목을 받을 다른 시간이 내 시간대 안에 없어요"); return }
        val labels = options.map { "${it.slot.at.format(timeFmt)} · ${it.shop.name} · ${it.items.size}/${visit.items.size}개 · ${fmtWon(it.confirmedCost)}${if (it.unpriced.isNotEmpty()) " + 견적" else ""}" }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("다른 방문 후보 (기한 안)")
            .setItems(labels.toTypedArray()) { _, which -> if (allowed) requestVisit(options[which]) }
            .setNegativeButton("닫기", null).show()
    }

    // ---------- 03 방문·예약 ----------
    private fun booking(c: LinearLayout) {
        c.add(header("방문·예약", "샘플 예약이에요 · 실제 정비소로 전송되지 않아요"))
        c.add(card(panel2) {
            add(text("샘플 정비소의 다음 응답", 16f, ink, true))
            add(grid(Scenario.entries.map { s -> toggle(s.label, state.scenario == s) { state.scenario = s; save(); render() } }, 5), top = 10)
            add(text("요청·대기는 확정으로 표시하지 않아요. 응답이 없으면 새 요청 대신 예약을 조회해요.", 14f, muted), top = 8)
        }, top = 16)
        val active = state.activeBooking()
        if (active != null) c.add(bookingCard(active, true), top = 12)
        else c.add(card {
            add(text("진행 중인 예약이 없어요", 19f, ink, true))
            add(buttons(button("정비 계획 보기", primary = true) { tab = Tab.PLAN; render() }), top = 10)
        }, top = 12)
        val past = state.bookingsOf().filter { !it.state.active }.reversed().take(5)
        if (past.isNotEmpty()) c.add(text("지난 예약", 18f, muted, true), top = 20)
        past.forEach { c.add(bookingCard(it, false), top = 10) }
    }

    private fun bookingCard(b: Booking, full: Boolean) = card(panel, if (full) bookingColor(b.state) else null) {
        add(row().apply {
            add(text("${b.shopName} · ${b.at.format(timeFmt)}", if (full) 21f else 17f, ink, true), weight = 1f)
            if (b.auto) add(chip("자동 요청", sky))
            add(chip("샘플 예약", amber))
            add(chip(b.state.label, bookingColor(b.state)))
        })
        add(text(b.ruleIds.mapNotNull { RuleBook.rule(it)?.title }.joinToString(" · "), 15f, muted), top = 6)
        add(text("확정 비용 ${fmtWon(b.confirmedCost)}" + if (b.unpriced > 0) " + 견적 필요 ${b.unpriced}건" else "", 15f, muted), top = 2)
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        (if (full) b.events else b.events.takeLast(1)).forEach { e ->
            add(text("${java.time.Instant.ofEpochMilli(e.atMillis).atZone(java.time.ZoneId.systemDefault()).format(fmt)}  ${e.text}", 14f, ink), top = 6)
        }
        if (!full) return@card
        val now = System.currentTimeMillis()
        val actions = when (b.state) {
            BookingState.REQUESTED, BookingState.PENDING -> listOf(button("요청 취소") { state.replaceBooking(BookingFlow.cancel(b, now)); save(); render() })
            BookingState.UNKNOWN -> listOf(
                button("예약 조회", primary = true) { state.replaceBooking(BookingFlow.lookup(b, now)); save(); render() },
                button("요청 취소") { state.replaceBooking(BookingFlow.cancel(b, now)); save(); render() })
            BookingState.CONFIRMED -> listOf(
                button("방문 결과 입력", primary = true) { resultDialog(b) },
                button("일정 변경") { changeDialog(b) },
                button("예약 취소") { state.replaceBooking(BookingFlow.cancel(b, now)); save(); render() })
            else -> emptyList()
        }
        if (actions.isNotEmpty()) add(buttons(*actions.toTypedArray()), top = 14)
    }

    private fun changeDialog(b: Booking) {
        val t = today()
        val items = state.plans(t).filter { it.rule.id in b.ruleIds }
        val shop = SampleData.shop(b.shopId) ?: return
        val options = Planner.candidates(items, listOf(shop), state.prefsOf(), t, now())
            .filter { it.items.size == items.size && it.slot.at != b.at }.take(8)
        if (options.isEmpty()) { notify("같은 정비소에 기한 안 다른 시간이 없어요"); return }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("${shop.name} · 바꿀 시간")
            .setItems(options.map { it.slot.at.format(timeFmt) }.toTypedArray()) { _, which ->
                if (allowed) { state.replaceBooking(BookingFlow.change(b, options[which].slot.at, System.currentTimeMillis())); save(); render() }
            }.setNegativeButton("닫기", null).show()
    }

    private fun resultDialog(b: Booking) {
        val t = today()
        val visitDay = b.at.toLocalDate()
        if (t.isBefore(visitDay)) {
            val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("방문일 이후에 입력해요")
                .setMessage("정비 결과는 방문(${visitDay.format(dateFmt)}) 뒤에 실제로 받은 작업만 기록해요.")
                .setNegativeButton("닫기", null)
            if (state.sampleMode) builder.setPositiveButton("샘플 날짜를 방문일로") { _, _ -> if (allowed) { advance(DAYS.between(t, visitDay).toInt()); resultDialog(b) } }
            dialog = builder.show(); return
        }
        val rules = b.ruleIds.mapNotNull { RuleBook.rule(it) }
        val checked = BooleanArray(rules.size)
        val km = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText((state.currentKm() ?: 0).toString()); setTextColor(ink); textSize = 18f
        }
        val box = column().apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            add(text("실제로 받은 작업만 선택하세요. 예약만으로는 기록을 만들지 않아요.", 15f, muted))
            rules.forEachIndexed { i, r -> add(toggleRow(r.title) { on -> checked[i] = on }, top = 8) }
            add(text("정비 당시 주행거리 (km)", 15f, muted), top = 14); add(km)
        }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("방문 결과 · ${t.format(dateFmt)}").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("결과 반영") { _, _ ->
                if (!allowed) return@setPositiveButton
                val value = km.text.toString().toIntOrNull()
                if (value == null) { notify("주행거리를 숫자로 입력해 주세요"); return@setPositiveButton }
                val done = rules.filterIndexed { i, _ -> checked[i] }.map { it.id }
                val added = state.applyResult(b.id, done, t, value, System.currentTimeMillis())
                save(); maybeAutoBook()
                notify("정비 결과 ${added}건을 반영하고 다음 관리 시점을 다시 계산했어요" + if (done.size < rules.size) " · 받지 않은 ${rules.size - done.size}건은 계속 관리해요" else "")
                tab = Tab.HOME; render()
            }.setNegativeButton("취소", null).show()
    }

    private fun toggleRow(label: String, changed: (Boolean) -> Unit): View {
        var on = false
        return Button(this).apply {
            isAllCaps = false; textSize = 16f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0)
            fun paint() { text = (if (on) "☑  " else "☐  ") + label; setTextColor(if (on) mint else ink); background = rounded(panel2, 10, if (on) mint else line) }
            paint(); setOnClickListener { on = !on; changed(on); paint() }
        }
    }

    // ---------- 04 정비 기록 ----------
    private fun records(c: LinearLayout) {
        c.add(header("정비 기록", "점검·교체 기록과 출처 · 예약만으로는 기록이 생기지 않아요"))
        c.add(buttons(button("기록 추가", primary = true) { pickRule() }), top = 14)
        val list = state.recordsOf().sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.km })
        if (list.isEmpty()) c.add(card { add(text("아직 기록이 없어요.", 17f, muted)) }, top = 12)
        list.forEach { r ->
            val title = RuleBook.rules.find { it.item.id == r.itemId && it.action == r.action }?.title
                ?: "${RuleBook.item(r.itemId)?.name ?: r.itemId} ${r.action.label}"
            c.add(card {
                add(row().apply {
                    add(text(title, 18f, ink, true), weight = 1f)
                    if (r.edited) add(chip("수정됨", sky))
                    add(chip(r.source.label, if (r.source == Source.SAMPLE) amber else mint))
                })
                add(text(if (r.fromNew) "출고 후 미실시 확인 · 출고일 ${r.date} 기준 (최초 주기 적용)" else "${r.date} · ${fmtKm(r.km)} km", 15f, muted), top = 4)
                add(buttons(
                    button("수정", enabled = !r.fromNew) { RuleBook.rules.find { it.item.id == r.itemId && it.action == r.action }?.let { recordDetail(r, it) } },
                    button("삭제") { confirm("이 기록을 삭제할까요?", "$title · ${r.date}") { state.deleteRecord(r.id); save(); render() } },
                ), top = 10)
            }, top = 10)
        }
        val readings = state.readingsOf().sortedByDescending { it.atMillis }.take(6)
        c.add(card(panel2) {
            add(text("주행거리 기록 (최근 ${readings.size}건)", 17f, ink, true))
            readings.forEach { add(text("${it.date} · ${fmtKm(it.km.toInt())} km · ${it.source.label}", 15f, muted), top = 4) }
        }, top = 16)
    }

    private fun pickRule() {
        val rules = RuleBook.rules
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("어떤 작업을 기록할까요?")
            .setItems(rules.map { it.title }.toTypedArray()) { _, which -> if (allowed) recordDetail(null, rules[which]) }
            .setNegativeButton("닫기", null).show()
    }

    private fun recordDetail(existing: Record?, rule: Rule) {
        var date = existing?.date ?: today()
        val km = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText((existing?.km ?: state.currentKm() ?: 0).toString()); setTextColor(ink); textSize = 18f
        }
        val dateButton = Button(this).apply { isAllCaps = false; textSize = 16f; setTextColor(ink); background = rounded(panel2, 10, line) }
        fun paint() { dateButton.text = "작업 날짜: $date (바꾸기)" }
        paint()
        dateButton.setOnClickListener {
            DatePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert, { _, y, m, d -> date = LocalDate.of(y, m + 1, d); paint() }, date.year, date.monthValue - 1, date.dayOfMonth)
                .apply { datePicker.maxDate = clockMillis() }.show()
        }
        val box = column().apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            add(text("${rule.item.name} · ${rule.action.label}", 18f, ink, true))
            add(text("기준표: ${rule.normalText}", 14f, muted), top = 4)
            add(dateButton, top = 12)
            add(text("작업 당시 주행거리 (km)", 15f, muted), top = 12); add(km)
            add(text("출처: 사용자 확인", 14f, muted), top = 8)
        }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (existing == null) "정비 기록 추가" else "정비 기록 수정").setView(box)
            .setPositiveButton("저장") { _, _ ->
                if (!allowed) return@setPositiveButton
                val value = km.text.toString().toIntOrNull()
                when {
                    value == null || value < 0 -> notify("주행거리를 숫자로 입력해 주세요")
                    date.isAfter(today()) -> notify("미래 날짜는 기록할 수 없어요")
                    existing == null -> if (state.addRecord(Record(state.nextId("r"), state.vehicleId, rule.item.id, rule.action, date, value, Source.USER)))
                        { save(); maybeAutoBook(); notify("${rule.title} 기록을 저장하고 일정을 다시 계산했어요") } else notify("같은 기록이 이미 있어요 · 중복 저장하지 않았어요")
                    else -> if (state.editRecord(existing.id, existing.action, date, value)) { save(); maybeAutoBook(); notify("기록을 수정하고 일정을 다시 계산했어요") }
                        else notify("같은 기록이 이미 있어요")
                }
                render()
            }.setNegativeButton("취소", null).show()
    }

    private fun confirm(title: String, body: String, action: () -> Unit) {
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setMessage(body)
            .setPositiveButton("확인") { _, _ -> if (allowed) action() }.setNegativeButton("취소", null).show()
    }

    // ---------- 05 내 조건 ----------
    private fun conditions(c: LinearLayout) {
        val p = state.prefsOf()
        val v = state.vehicle
        fun set(np: Preferences) { state.updatePrefs(np); save(); maybeAutoBook(); render() }
        c.add(header("내 조건", "방문 계획과 자동 예약은 여기서 정한 범위 안에서만 움직여요"))
        c.add(card {
            add(text("방문 가능한 시간대", 18f, ink, true))
            add(grid(TimeBlock.entries.map { b -> toggle(b.label, b in p.blocks) { set(p.copy(blocks = if (b in p.blocks) p.blocks - b else p.blocks + b)) } }, 5), top = 10)
        }, top = 16)
        c.add(row().apply {
            gravity = Gravity.TOP
            add(card {
                add(text("방문 1회 예산", 18f, ink, true))
                add(row().apply {
                    add(button("−") { set(p.copy(budget = (p.budget - 50_000).coerceAtLeast(50_000))) })
                    add(text(fmtWon(p.budget), 22f, ink, true).apply { gravity = Gravity.CENTER }, weight = 1f)
                    add(button("+") { set(p.copy(budget = (p.budget + 50_000).coerceAtMost(1_000_000))) })
                }, top = 10)
            }, weight = 1f)
            add(card {
                add(text("선호 정비소", 18f, ink, true))
                SampleData.shops.forEach { s -> add(toggle("${s.name} · ${s.area}", s.id in p.preferredShops) {
                    set(p.copy(preferredShops = if (s.id in p.preferredShops) p.preferredShops - s.id else p.preferredShops + s.id))
                }, top = 8) }
            }, weight = 1.4f)
        }, top = 12)
        c.add(card(panel, if (p.auto.enabled) mint else null) {
            add(row().apply {
                add(text("조건부 자동 예약", 18f, ink, true), weight = 1f)
                add(toggle(if (p.auto.enabled) "켜짐" else "꺼짐", p.auto.enabled) { set(p.copy(auto = p.auto.copy(enabled = !p.auto.enabled))) })
            })
            add(row().apply {
                add(text("자동 예약 한도", 16f, muted), weight = 1f)
                add(button("−") { set(p.copy(auto = p.auto.copy(maxCost = (p.auto.maxCost - 50_000).coerceAtLeast(50_000)))) })
                add(text(fmtWon(p.auto.maxCost), 20f, ink, true))
                add(button("+") { set(p.copy(auto = p.auto.copy(maxCost = (p.auto.maxCost + 50_000).coerceAtMost(1_000_000)))) })
            }, top = 10)
            add(text("선호 정비소 · 가격 확정 · 한도와 예산 안 · 오늘 확인한 주행거리일 때만 자동으로 요청해요. 거절·마감되면 다시 자동 요청하지 않고 알려드려요.", 14f, muted), top = 8)
        }, top = 12)
        c.add(card {
            add(text("사용 환경 (취급설명서 가혹 조건)", 18f, ink, true))
            add(text("1·3·4·8·9·10번은 운행 이력으로 자동 판정해요(02 운행 이력). 차량 신호로 알 수 없는 조건은 직접 골라 주세요.", 14f, muted), top = 4)
            val detected = state.profile(today()).detected - v.excluded
            if (detected.isNotEmpty()) add(text("운행 이력으로 적용 중: ${detected.sorted().joinToString(",")}번", 14f, amber), top = 4)
            add(grid(RuleBook.severeConditions.mapIndexed { i, label -> val n = i + 1
                toggle("$n. $label" + if (n in detected) " · 운행 이력" else "", n in v.severe) { state.updateVehicle(v.copy(severe = if (n in v.severe) v.severe - n else v.severe + n)); save(); maybeAutoBook(); render() }.apply { textSize = 14f; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            }, 2), top = 10)
        }, top = 12)
        c.add(card {
            add(text("차량 정보", 18f, ink, true))
            add(text("${v.model} ${v.year} · ${v.powertrain} · 출고일 ${v.deliveryDate ?: "미입력"} · ${if (v.profileConfirmed) "차종 확인됨" else "차종 확인 전"}", 15f, muted), top = 4)
            if (!v.sample) add(buttons(
                button("출고일 입력") {
                    val d = v.deliveryDate ?: today()
                    DatePickerDialog(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert, { _, y, m, dd -> state.updateVehicle(state.vehicle.copy(deliveryDate = LocalDate.of(y, m + 1, dd))); save(); render() }, d.year, d.monthValue - 1, d.dayOfMonth)
                        .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                },
                button(if (v.profileConfirmed) "차종 확인 취소" else "${RuleBook.MODEL} ${RuleBook.YEAR}이 맞아요") { state.updateVehicle(v.copy(profileConfirmed = !v.profileConfirmed)); save(); render() },
            ), top = 10)
        }, top = 12)
        c.add(text("계획 설정(앱 기준): 곧 도래 ${p.soonDays}일 또는 ${fmtKm(p.soonKm)} km 이내 · 최대 앞당김 ${p.advanceDays}일 · 기한 지난 항목 ${p.overdueDays}일 안 방문 · 계획 범위 ${p.horizonDays}일 · 예상 날짜는 ${Planner.RATE_MIN_SPAN_DAYS}일 이상 운행 기록 필요", 14f, muted), top = 14)
    }

    // ---------- 06 샘플·연결 ----------
    private fun sample(c: LinearLayout) {
        c.add(header("샘플·연결", "시연용 샘플 입력과 실제 차량 신호를 구분해요"))
        c.add(card {
            add(text("입력 모드", 18f, ink, true))
            add(row().apply {
                add(toggle("샘플 차량", state.sampleMode) { setMode(true) }, weight = 1f)
                add(toggle("실제 차량 입력", !state.sampleMode) { setMode(false) }, weight = 1f)
            }, top = 10)
            add(text(if (state.sampleMode) "가상 차량·이력·정비소로 흐름을 재현해요. 실제 차량 신호는 읽지 않아요."
                else "차량의 누적 주행거리·트립·타이어 공기압을 지원되는 범위에서 읽어 '내 차량'에만 반영해요.", 14f, muted), top = 8)
        }, top = 16)
        if (state.sampleMode) sampleControls(c) else realStatus(c)
    }

    private fun setMode(sample: Boolean) {
        if (state.sampleMode == sample) return
        state.sampleMode = sample
        state.vehicleId = if (sample) SampleData.A else SampleData.REAL
        if (sample) { inputs.stop(); inputsRunning = false; builder = null; odo = OdoSignal(); trip = TripSignal(); realTires = emptyMap(); brakeFluid = null; signals.clear() }
        else startRealInputs()
        save(); maybeAutoBook(); render()
    }

    private fun sampleControls(c: LinearLayout) {
        val t = today()
        c.add(card {
            add(text("샘플 차량", 18f, ink, true))
            add(row().apply {
                add(toggle("가상 차량 A · GN7HEV 2023", state.vehicleId == SampleData.A) { state.vehicleId = SampleData.A; save(); render() }, weight = 1f)
                add(toggle("가상 차량 B · 기준 미등록 차종", state.vehicleId == SampleData.B) { state.vehicleId = SampleData.B; save(); render() }, weight = 1f)
            }, top = 10)
        }, top = 12)
        c.add(row().apply {
            gravity = Gravity.TOP
            add(card {
                add(text("샘플 날짜 · ${t.format(dateFmt)}${if (state.dayOffset > 0) " (+${state.dayOffset}일)" else ""}", 18f, ink, true))
                add(text("날짜를 넘기면 평소 운행량만큼 주행거리도 늘어나요", 14f, muted), top = 4)
                add(buttons(button("+1일") { advance(1) }, button("+7일") { advance(7) }, button("+30일") { advance(30) }), top = 10)
            }, weight = 1f)
            add(card {
                add(text("샘플 주행거리 · ${state.currentKm()?.let { fmtKm(it) } ?: "-"} km", 18f, ink, true))
                add(buttons(button("+300 km") { drive(300) }, button("+1,000 km") { drive(1_000) }), top = 10)
                add(buttons(
                    button("감소 신호") { reading((state.currentKm() ?: 0) - 5_000.0) },
                    button("같은 값 재수신") { reading((state.lastReading()?.km ?: 0.0)) },
                ), top = 8)
            }, weight = 1f)
        }, top = 12)
        c.add(card {
            add(text("샘플 차량 경고 신호", 18f, ink, true))
            add(row().apply {
                add(toggle("타이어 앞 왼쪽 저압", sampleTireLow) { sampleTireLow = !sampleTireLow; render() }, weight = 1f)
                add(toggle("브레이크 오일 레벨 부족", sampleFluidLow) { sampleFluidLow = !sampleFluidLow; render() }, weight = 1f)
            }, top = 10)
        }, top = 12)
        c.add(card(panel2) {
            add(text("샘플 처음 상태로", 18f, ink, true))
            add(text("가상 차량·기록·예약·조건을 처음 값으로 되돌려요. 실제 차량 기록은 지워지지 않게 따로 보관해요.", 14f, muted), top = 4)
            add(buttons(button("샘플 초기화") { confirm("샘플을 처음 상태로 되돌릴까요?", "샘플 차량의 기록·예약·조건이 초기화돼요.") { resetSample() } }), top = 10)
        }, top = 12)
    }

    private fun resetSample() {
        val keepVehicle = state.vehicles.find { it.id == SampleData.REAL }
        val keepRecords = state.records.filter { it.vehicleId == SampleData.REAL }
        val keepReadings = state.readings[SampleData.REAL]
        val keepPrefs = state.prefs[SampleData.REAL]
        val keepBookings = state.bookings.filter { it.vehicleId == SampleData.REAL }
        val keepTrips = state.trips.filter { it.vehicleId == SampleData.REAL }
        val seq = state.seq
        SampleData.seed(state, LocalDate.now(), System.currentTimeMillis())
        state.seq = maxOf(state.seq, seq)
        keepVehicle?.let { state.updateVehicle(it) }
        state.records += keepRecords
        keepReadings?.let { state.readings[SampleData.REAL] = it }
        keepPrefs?.let { state.prefs[SampleData.REAL] = it }
        state.bookings += keepBookings
        keepTrips.forEach { state.addTrip(it) }
        sampleTireLow = false
        save(); maybeAutoBook(); notify("샘플을 처음 상태로 되돌렸어요"); tab = Tab.HOME; render()
    }

    /** Moves the sample clock forward; the sample car drives its usual pattern on each passed day. */
    private fun advance(days: Int) {
        if (days <= 0) return
        val before = state.effectiveSevere(today())
        var km = state.lastReading()?.km ?: 0.0
        val zone = java.time.ZoneId.systemDefault()
        for (d in 1..days) {
            val date = today().plusDays(d.toLong())
            val trips = SampleData.tripsOn(state.vehicleId, state.pattern, date)
            trips.forEach { state.addTrip(it) }
            km += trips.sumOf { it.km }
            state.addReading(state.vehicleId, km, Source.SAMPLE, date.atTime(22, 0).atZone(zone).toInstant().toEpochMilli(), date)
        }
        state.dayOffset += days
        save(); maybeAutoBook()
        val after = state.effectiveSevere(today())
        if (after != before) notify("운행 이력이 쌓여 맞춤 판정이 바뀌었어요: 가혹 조건 ${after.sorted().joinToString(",").ifEmpty { "없음" }}")
        render()
    }

    private fun setPattern(p: DrivePattern) {
        val before = state.effectiveSevere(today())
        SampleData.rebuildDriving(state, state.vehicleId, p, today(), clockMillis(), state.lastReading()?.km ?: 41_600.0)
        save(); maybeAutoBook()
        val after = state.effectiveSevere(today())
        notify("샘플 운행 패턴 '${p.label}' 30일을 분석했어요 → " + if (after.isEmpty()) "통상 조건 주기로 관리" else "가혹 조건 ${after.sorted().joinToString(",")}번 주기로 맞춤" +
            if (after != before) " (계획이 바뀌었어요)" else "")
        render()
    }

    /** One sample drive that just ended, as if the driver parked now. */
    private fun sampleTrip() {
        val base = SampleData.tripsOn(state.vehicleId, state.pattern, today()).firstOrNull()
            ?: SampleData.tripsOn(state.vehicleId, state.pattern, today().minusDays(1)).first()
        val end = clockMillis()
        val start = end - (base.minutes * 60_000).toLong()
        val zoned = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.systemDefault())
        val t = base.copy(id = state.nextId("st"), startMillis = start, endMillis = end, date = zoned.toLocalDate(), startHour = zoned.hour)
        val before = state.effectiveSevere(today())
        state.addReading(state.vehicleId, (state.lastReading()?.km ?: 0.0) + t.km, Source.SAMPLE, end, today())
        afterTrip(t, before)
        render()
    }

    private fun drive(km: Int) = reading((state.lastReading()?.km ?: 0.0) + km)

    private fun reading(km: Double) {
        when (state.addReading(state.vehicleId, km, Source.SAMPLE, clockMillis(), today())) {
            ReadingResult.DECREASE -> notify("주행거리가 이전보다 작게 들어왔어요 · 반영하지 않았어요 (교체로 해석하지 않음)")
            ReadingResult.UNCHANGED -> notify("같은 주행거리를 다시 받았어요 · 기록을 늘리지 않았어요")
            ReadingResult.ACCEPTED -> { notify("주행거리 ${fmtKm(km.toInt())} km를 반영하고 일정을 다시 계산했어요"); maybeAutoBook() }
        }
        save(); render()
    }

    private fun realStatus(c: LinearLayout) {
        c.add(liveCard(), top = 12)
        brakeFluid?.let { f -> c.add(card { add(text("브레이크 오일 레벨 (Pleos Safety): $f", 17f, if (f == "부족" || f == "매우 부족") coral else ink, true)) }, top = 12) }
        c.add(card {
            add(text("누적 주행거리 (Pleos Odometer)", 18f, ink, true))
            add(text(odo.km?.let { "${"%,.1f".format(it)} km" } ?: "값 없음", 28f, ink, true), top = 6)
            add(text(odo.detail + if (odo.atMillis > 0) " · ${java.time.Instant.ofEpochMilli(odo.atMillis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)} 수신" else "", 14f, muted), top = 4)
            add(text("현재 트립: ${trip.km?.let { "%.1f km".format(it) } ?: "-"} · ${trip.minutes?.let { "${it}분" } ?: "-"} · ${trip.detail}", 15f, muted), top = 8)
            add(text("트립 거리 초기화나 감소 신호는 정비 기록으로 해석하지 않아요.", 14f, muted), top = 4)
            add(buttons(button("다시 연결") { inputs.start() }, button("주행거리 직접 입력") { manualOdometer() }), top = 12)
        }, top = 12)
        c.add(card {
            add(text("타이어 공기압 (Pleos Tire)", 18f, ink, true))
            if (realTires.isEmpty()) add(text("확인 중이거나 값이 없어요", 15f, muted), top = 6)
            realTires.values.forEach { s ->
                add(text("${s.wheel}: ${s.pressure?.let { "%.1f".format(it) } ?: "-"} (저압 기준 ${s.low?.let { "%.1f".format(it) } ?: "-"}) · ${s.detail}${if (s.warning) " · 확인 필요" else ""}", 15f, if (s.warning) coral else muted), top = 6)
            }
            add(text("단위는 차량이 보낸 값 그대로 표시해요. 공기압으로 마모를 판단하지 않아요.", 14f, muted), top = 6)
        }, top = 12)
    }

    private fun manualOdometer() {
        val km = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText((state.currentKm() ?: 0).toString()); setTextColor(ink); textSize = 18f }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("계기판 주행거리 입력").setView(column().apply { setPadding(dp(24), dp(8), dp(24), 0); add(km) })
            .setPositiveButton("저장") { _, _ ->
                val value = km.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                when (state.addReading(state.vehicleId, value, Source.USER, System.currentTimeMillis(), today())) {
                    ReadingResult.DECREASE -> notify("이전 기록보다 작아 반영하지 않았어요")
                    else -> { save(); maybeAutoBook(); notify("주행거리를 반영했어요 (사용자 확인)") }
                }
                render()
            }.setNegativeButton("취소", null).show()
    }
}
