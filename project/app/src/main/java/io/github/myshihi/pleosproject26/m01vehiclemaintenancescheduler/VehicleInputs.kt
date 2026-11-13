package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import ai.pleos.playground.vehicle.Vehicle as PleosVehicle
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.Tire
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Live driving signals used to build trips. Status lines tell the user which signals are actually available. */
sealed class DriveEvent {
    data class Gear(val park: Boolean, val label: String) : DriveEvent()
    data class Speed(val kph: Double, val atMillis: Long) : DriveEvent()
    data class Brake(val pressed: Boolean) : DriveEvent()
    data class Temp(val celsius: Double) : DriveEvent()
    data class BrakeFluid(val state: String) : DriveEvent()
    data class Status(val key: String, val text: String) : DriveEvent()
}

fun gearLabel(g: VehicleGear) = when (g) {
    VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; VehicleGear.GEAR_UNKNOWN -> "?"; else -> "D"
}

fun fluidLabel(s: OilLevelState) = when (s) {
    OilLevelState.NORMAL -> "정상"; OilLevelState.HIGH -> "높음"; OilLevelState.LOW -> "부족"
    OilLevelState.CRITICALLY_LOW -> "매우 부족"; else -> "오류"
}

data class OdoSignal(val km: Float? = null, val supported: Boolean? = null, val detail: String = "확인 전", val atMillis: Long = 0)
data class TripSignal(val km: Float? = null, val minutes: Int? = null, val detail: String = "확인 전")
data class TireSignal(val wheel: String, val pressure: Float?, val low: Float?, val detail: String) {
    /** Pressure at or below the vehicle's low-pressure value. Never interpreted as tread wear. */
    val warning get() = pressure != null && low != null && pressure <= low
}

fun wheelLabel(value: WheelValue) = when (value) {
    WheelValue.LEFT_FRONT -> "앞 왼쪽"; WheelValue.RIGHT_FRONT -> "앞 오른쪽"
    WheelValue.LEFT_REAR -> "뒤 왼쪽"; WheelValue.RIGHT_REAR -> "뒤 오른쪽"; else -> "알 수 없음"
}

/**
 * Reads the odometer, current trip and tire pressure where the vehicle supports them. Read-only: nothing is written
 * to the car. Units are shown as received (km assumed for distance, unit of pressure not verified).
 */
class VehicleInputs(
    private val context: Context,
    private val odometer: (OdoSignal) -> Unit,
    private val trip: (TripSignal) -> Unit,
    private val tires: (Map<String, TireSignal>) -> Unit,
    private val drive: (DriveEvent) -> Unit = {},
) {
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var gearListener: GearSelectionListener? = null
    private var brakeListener: BrakePedalStatusListener? = null
    private var fluidListener: OilLevelStateListener? = null
    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val v = (value.value as? Float) ?: return
            val at = System.currentTimeMillis()
            handler.post {
                when (value.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> drive(DriveEvent.Speed(kotlin.math.abs(v) * 3.6, at))
                    VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> drive(DriveEvent.Temp(v.toDouble()))
                }
            }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) { }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: PleosVehicle? = null
    private var odo: Odometer? = null
    private var listener: DistanceDrivenListener? = null
    private var generation = 0
    private val tireValues = mutableMapOf<String, TireSignal>()
    private var tripKm: Float? = null
    private var tripMin: Int? = null
    private var tripDetail = "확인 전"

    private fun post(token: Int, action: () -> Unit) { handler.post { if (token == generation) action() } }
    private fun readable(c: CapabilityConfig) = c.accessType == AccessType.READ || c.accessType == AccessType.READ_WRITE

    fun start() {
        stop()
        val token = generation
        odometer(OdoSignal(detail = "주행거리 지원 확인 중"))
        try {
            val v = PleosVehicle(context.applicationContext).also { it.initialize() }
            vehicle = v
            startOdometer(v.getOdometer(), token)
            startTires(v.getTire(), token)
            startDrive(v, token)
        } catch (_: Exception) {
            post(token) { odometer(OdoSignal(supported = false, detail = "차량 연결 실패 · 수동 입력 가능")) }
        } catch (_: LinkageError) {
            post(token) { odometer(OdoSignal(supported = false, detail = "차량 SDK를 쓸 수 없는 기기")) }
        }
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (token != generation) return
                odo?.let { readOdometer(it, token); readTrip(it, token) }
                vehicle?.let { v -> try { readTires(v.getTire(), token) } catch (_: Exception) { } }
                handler.postDelayed(this, 60_000)
            }
        }, 60_000)
    }

    private fun startOdometer(o: Odometer, token: Int) {
        o.checkCurrentDistanceDrivenCapabilities({ config -> post(token) {
            if (!readable(config)) { odometer(OdoSignal(supported = false, detail = "누적 주행거리 읽기 미지원 · 수동 입력 가능")); return@post }
            odo = o
            readOdometer(o, token)
            val l = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(value: Float?) = post(token) { showOdo(value) }
                override fun onFailed(e: Exception) = post(token) { odometer(OdoSignal(supported = true, detail = "주행거리 구독 실패 · 1분마다 조회")) }
            }
            try { o.registerDistanceDriven(l); listener = l } catch (_: Exception) { }
            readTrip(o, token)
        } }, { post(token) { odometer(OdoSignal(supported = false, detail = "주행거리 지원 확인 실패 · 수동 입력 가능")) } })
    }

    private fun showOdo(value: Float?) = odometer(OdoSignal(value, true,
        if (value == null) "주행거리 값 없음" else "누적 주행거리 raw=$value (km로 표시)", System.currentTimeMillis()))

    /** Reads the odometer once more now (used when the car is parked, so the drive ends with the latest distance). */
    fun refreshOdometer() { odo?.let { readOdometer(it, generation) } }

    private fun readOdometer(o: Odometer, token: Int) {
        try {
            o.getCurrentDistanceDriven({ value -> post(token) { showOdo(value) } },
                { post(token) { odometer(OdoSignal(supported = true, detail = "주행거리 조회 실패 · 다시 시도 중")) } })
        } catch (_: Exception) { odometer(OdoSignal(supported = true, detail = "주행거리 조회 실패")) }
    }

    private fun readTrip(o: Odometer, token: Int) {
        fun publish() = trip(TripSignal(tripKm, tripMin, tripDetail))
        try {
            o.checkTripCurrentDrivingDistanceCapabilities({ c -> post(token) {
                if (readable(c)) o.getTripCurrentDrivingDistance({ v -> post(token) { tripKm = v; tripDetail = "현재 트립"; publish() } },
                    { post(token) { tripDetail = "트립 거리 조회 실패"; publish() } })
                else { tripDetail = "트립 거리 미지원"; publish() }
            } }, { post(token) { tripDetail = "트립 지원 확인 실패"; publish() } })
            o.checkTripCurrentDrivingTimeCapabilities({ c -> post(token) {
                if (readable(c)) o.getTripCurrentDrivingTime({ v -> post(token) { tripMin = v; publish() } }, { })
            } }, { })
        } catch (_: Exception) { tripDetail = "트립 조회 실패"; publish() }
    }

    private fun startTires(t: Tire, token: Int) {
        try { readTires(t, token) } catch (_: Exception) { tires(mapOf("상태" to TireSignal("상태", null, null, "타이어 공기압 조회 실패"))) }
    }

    private fun readTires(t: Tire, token: Int) {
        t.checkTirePressureCapabilities({ config, areas -> post(token) {
            if (!readable(config) || areas.isEmpty()) {
                tireValues.clear(); tireValues["상태"] = TireSignal("상태", null, null, "타이어 공기압 읽기 미지원")
                tires(tireValues.toMap()); return@post
            }
            tireValues.remove("상태")
            areas.forEach { area -> readWheel(t, area, token) }
        } }, { post(token) { tireValues["상태"] = TireSignal("상태", null, null, "타이어 지원 확인 실패"); tires(tireValues.toMap()) } })
    }

    private fun readWheel(t: Tire, area: WheelArea, token: Int) {
        val name = area.values.joinToString("·") { wheelLabel(it) }
        fun update(f: (TireSignal) -> TireSignal) {
            tireValues[name] = f(tireValues[name] ?: TireSignal(name, null, null, "조회 중")); tires(tireValues.toMap())
        }
        try {
            t.getTirePressure(area, { v -> post(token) { update { it.copy(pressure = v, detail = "공기압 raw=$v") } } },
                { post(token) { update { it.copy(detail = "공기압 조회 실패") } } })
            t.getLowTirePressure(area, { v -> post(token) { update { it.copy(low = v) } } }, { })
        } catch (_: Exception) { update { it.copy(detail = "공기압 조회 실패") } }
    }

    private fun startDrive(v: PleosVehicle, token: Int) {
        try {
            val gear = v.getGear()
            val l = object : GearSelectionListener {
                override fun onSelected(g: VehicleGear?) = post(token) { if (g != null) drive(DriveEvent.Gear(g == VehicleGear.GEAR_PARK, gearLabel(g))) }
                override fun onFailed(e: Exception) = post(token) { drive(DriveEvent.Status("gear", "기어 구독 실패")) }
            }
            gear.registerGearSelection(l); gearListener = l
            gear.getCurrentGearSelectionState({ g -> post(token) { if (g != null) drive(DriveEvent.Gear(g == VehicleGear.GEAR_PARK, gearLabel(g))); drive(DriveEvent.Status("gear", "기어 수신 중")) } },
                { post(token) { drive(DriveEvent.Status("gear", "기어 조회 실패 · 운행 구분 불가")) } })
        } catch (_: Exception) { drive(DriveEvent.Status("gear", "기어 연결 실패")) }
        try {
            val brake = v.getBrake()
            brake.checkBrakePedalStatusCapabilities({ c -> post(token) {
                if (!readable(c)) { drive(DriveEvent.Status("brake", "브레이크 페달 미지원")); return@post }
                val l = object : BrakePedalStatusListener {
                    override fun onBrakePedalStatusUpdated(pressed: Boolean?) = post(token) { if (pressed != null) drive(DriveEvent.Brake(pressed)) }
                    override fun onFailed(e: Exception) = post(token) { drive(DriveEvent.Status("brake", "브레이크 구독 실패")) }
                }
                try { brake.registerBrakePedalStatus(l); brakeListener = l; drive(DriveEvent.Status("brake", "브레이크 페달 수신 중")) } catch (_: Exception) { }
            } }, { post(token) { drive(DriveEvent.Status("brake", "브레이크 지원 확인 실패")) } })
        } catch (_: Exception) { drive(DriveEvent.Status("brake", "브레이크 연결 실패")) }
        try {
            val safety = v.getSafety()
            safety.checkBrakeFluidLevelStateCapabilities({ c, _ -> post(token) {
                if (!readable(c)) { drive(DriveEvent.Status("fluid", "브레이크 오일 레벨 미지원")); return@post }
                safety.getBrakeFluidLevelState({ s -> post(token) { if (s != null) drive(DriveEvent.BrakeFluid(fluidLabel(s))) } }, { })
                val l = object : OilLevelStateListener {
                    override fun onOilLevelUpdated(s: OilLevelState?) = post(token) { if (s != null) drive(DriveEvent.BrakeFluid(fluidLabel(s))) }
                    override fun onFailed(e: Exception) = post(token) { drive(DriveEvent.Status("fluid", "브레이크 오일 구독 실패")) }
                }
                try { safety.registerBrakeFluidLevelState(l); fluidListener = l; drive(DriveEvent.Status("fluid", "브레이크 오일 레벨 수신 중")) } catch (_: Exception) { }
            } }, { post(token) { drive(DriveEvent.Status("fluid", "브레이크 오일 지원 확인 실패")) } })
        } catch (_: Exception) { drive(DriveEvent.Status("fluid", "브레이크 오일 연결 실패")) }
        startProperties(token)
    }

    /** Vehicle speed and outside temperature come from the standard Android car property service. */
    private fun startProperties(token: Int) {
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (token != generation || !ready) return@createCar
                try {
                    val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    props = pm
                    if (context.checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
                        val ok = pm.registerCallback(propCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)
                        drive(DriveEvent.Status("speed", if (ok) "차속 수신 중" else "차속 구독 실패"))
                    } else drive(DriveEvent.Status("speed", "차속 권한 필요 · 공회전·고속 판정 불가"))
                    val tempOk = try { pm.registerCallback(propCallback, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, CarPropertyManager.SENSOR_RATE_NORMAL) } catch (_: Exception) { false }
                    drive(DriveEvent.Status("temp", if (tempOk) "외기온 수신 중" else "외기온 미지원"))
                } catch (_: Exception) { drive(DriveEvent.Status("speed", "차량 속성 서비스 연결 실패")) }
            }
        } catch (_: LinkageError) { drive(DriveEvent.Status("speed", "Android Automotive 기기가 필요해요")) }
        catch (_: Exception) { drive(DriveEvent.Status("speed", "차량 속성 서비스 연결 실패")) }
    }

    fun stop() {
        generation++
        handler.removeCallbacksAndMessages(null)
        try { props?.unregisterCallback(propCallback) } catch (_: Exception) { }
        try { car?.disconnect() } catch (_: Exception) { }
        props = null; car = null
        vehicle?.let { v ->
            gearListener?.let { l -> try { v.getGear().unregisterGearSelection(l) } catch (_: Exception) { } }
            brakeListener?.let { l -> try { v.getBrake().unregisterBrakePedalStatus(l) } catch (_: Exception) { } }
            fluidListener?.let { l -> try { v.getSafety().unregisterBrakeFluidLevelState(l) } catch (_: Exception) { } }
        }
        gearListener = null; brakeListener = null; fluidListener = null
        listener?.let { l -> try { odo?.unregisterDistanceDriven(l) } catch (_: Exception) { } }
        try { vehicle?.release() } catch (_: Exception) { }
        listener = null; odo = null; vehicle = null
        tireValues.clear(); tripKm = null; tripMin = null; tripDetail = "확인 전"
    }
}
