package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

/**
 * Manufacturer maintenance table for one vehicle. Values are copied from the official owner's manual pages below
 * (checked 2026-11-11). The table is applied only to a confirmed matching profile; other cars get no schedule.
 * Only a subset of rows is modelled; rows without a periodic number (daily checks etc.) are not scheduled.
 */
object RuleBook {
    const val MODEL = "GN7HEV"
    const val YEAR = 2023
    const val TITLE = "현대자동차 GN7HEV 2023 취급설명서 · 정기 점검 주기"
    const val CHECKED = "2026-11-11 확인"
    const val NORMAL_URL = "https://ownersmanual.hyundai.com/full_webhelp/GN7HEV/2023/ko_KR/id6b219dd157c.html"
    const val SEVERE_URL = "https://ownersmanual.hyundai.com/full_webhelp/GN7HEV/2023/ko_KR/id7b77f1c5ffe.html"

    /** 가혹 조건 1..12, in the manual's order. */
    val severeConditions = listOf(
        "짧은 거리를 반복적으로 주행",
        "모래, 먼지가 많은 지역 주행",
        "과다한 공회전 사용",
        "교통 체증이 심한 구역 주행",
        "잦은 험로(모랫길, 자갈길, 눈길, 비포장도로) 주행",
        "산길, 오르막길, 내리막길 주행",
        "경찰차, 택시, 상용차, 견인차 등으로 사용",
        "잦은 고속 주행 및 급가감속",
        "정지와 출발의 잦은 반복",
        "소금, 부식 물질 위 또는 한랭 지역 주행",
        "견인용, 캠핑용으로 사용 또는 지붕 위 짐칸 탑재 주행",
        "추천하지 않은 엔진 오일 주입(광유, 세미 합성유, 낮은 오일 등급 외)",
    )

    val engineOil = Item("engine_oil", "엔진 오일·오일 필터")
    val hsgBelt = Item("hsg_belt", "HSG 구동 벨트")
    val airCleaner = Item("air_cleaner", "에어클리너 필터")
    val cabinFilter = Item("cabin_filter", "공조 장치용 에어 필터")
    val tireRotation = Item("tire_rotation", "타이어 위치")
    val brakePad = Item("brake_pad", "브레이크 패드·디스크")
    val brakeFluid = Item("brake_fluid", "브레이크 오일")
    val coolant = Item("coolant", "냉각수")
    val aircon = Item("aircon", "에어컨 냉매·작동 상태")
    val sparkPlug = Item("spark_plug", "점화플러그")
    val atf = Item("atf", "자동변속기 오일")
    val items = listOf(engineOil, hsgBelt, airCleaner, cabinFilter, tireRotation, brakePad, brakeFluid, coolant, aircon, sparkPlug, atf)

    private val all = (1..12).toSet()
    private val hsgSevere = setOf(2, 3, 5, 7, 9, 10)

    val rules: List<Rule> = listOf(
        Rule(engineOil, Action.REPLACE, null, Interval(10_000, 12),
            SevereRule(Interval(5_000, 6), all, "매 5,000 km 또는 6개월마다 교체"), "매 10,000 km 또는 12개월마다 교체"),
        Rule(hsgBelt, Action.INSPECT, null, Interval(10_000, 12),
            SevereRule(Interval(5_000, 6), hsgSevere, "매 5,000 km 또는 6개월마다 점검"), "매 10,000 km 또는 12개월마다 점검"),
        Rule(hsgBelt, Action.REPLACE, null, Interval(100_000, 48),
            SevereRule(Interval(50_000, 24), hsgSevere, "매 50,000 km 또는 24개월마다 교체"), "매 100,000 km 또는 48개월마다 교체"),
        Rule(airCleaner, Action.INSPECT, null, Interval(10_000, null),
            SevereRule(null, setOf(2, 5), "상태에 따라 수시 점검 또는 필요시 교체"), "매 1만 km 점검"),
        Rule(airCleaner, Action.REPLACE, null, Interval(40_000, null),
            SevereRule(null, setOf(2, 5), "상태에 따라 수시 점검 또는 필요시 교체"), "매 4만 km 교체"),
        Rule(cabinFilter, Action.REPLACE, null, Interval(15_000, null),
            SevereRule(null, setOf(2), "상태에 따라 수시 점검 또는 필요시 교체"), "매 15,000 km마다 교체"),
        Rule(tireRotation, Action.REPLACE, null, Interval(10_000, null), null, "매 1만 km 위치 교환", actionLabel = "교환"),
        Rule(brakePad, Action.INSPECT, null, Interval(10_000, null),
            SevereRule(null, setOf(2, 4, 5, 6, 7, 8, 9, 10), "상태에 따라 수시 점검"), "매 1만 km 점검"),
        Rule(brakeFluid, Action.REPLACE, null, Interval(50_000, null), null, "매 50,000 km마다 교체"),
        Rule(coolant, Action.REPLACE, Interval(200_000, 120), Interval(40_000, 24), null,
            "최초 교체 20만 km 또는 10년, 이후 매 4만 km 또는 매 2년마다 교체"),
        Rule(aircon, Action.INSPECT, null, Interval(null, 12), null, "매 12개월마다 점검"),
        Rule(sparkPlug, Action.REPLACE, null, Interval(160_000, null),
            SevereRule(null, setOf(1, 3, 4, 6, 7, 9, 11), "상태에 따라 수시 점검"), "매 160,000 km마다 교체"),
        Rule(atf, Action.REPLACE, null, null,
            SevereRule(Interval(100_000, null), setOf(1, 4, 5, 6, 7, 8, 9, 11), "매 100,000 km 교체"), "통상 조건 무점검, 무교체"),
    )

    fun applies(vehicle: Vehicle) = vehicle.profileConfirmed && vehicle.model == MODEL && vehicle.year == YEAR
    fun rulesFor(vehicle: Vehicle): List<Rule> = if (applies(vehicle)) rules else emptyList()
    fun item(id: String) = items.find { it.id == id }
    fun rule(id: String) = rules.find { it.id == id }
}
