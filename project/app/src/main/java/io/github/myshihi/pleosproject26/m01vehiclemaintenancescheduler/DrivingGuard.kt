package io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Detailed input is offered only when the car reports no UX restriction. Missing state fails closed, also in sample mode. */
class DrivingGuard(private val context: Context, private val changed: (Boolean, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var generation = 0

    fun start() {
        stop()
        val token = generation
        changed(false, "정차 상태 확인 중")
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (token == generation) {
                    if (!ready) changed(false, "차량 UX 연결이 끊겼어요")
                    else try {
                        manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        manager?.registerListener { restrictions -> if (token == generation) apply(restrictions) }
                        apply(manager?.currentCarUxRestrictions)
                    } catch (_: Exception) { changed(false, "차량 UX 상태를 확인할 수 없어요") }
                }
            }
        } catch (_: LinkageError) { changed(false, "Android Automotive 기기가 필요해요") }
        catch (_: Exception) { changed(false, "차량 UX 서비스 연결을 확인해 주세요") }
    }

    private fun apply(restrictions: CarUxRestrictions?) {
        val allowed = restrictions != null && !restrictions.isRequiresDistractionOptimization &&
            restrictions.activeRestrictions == CarUxRestrictions.UX_RESTRICTIONS_BASELINE
        changed(allowed, if (allowed) "정차 중 · 계획 확인과 입력 가능" else "주행 중 · 확인만 가능")
    }

    fun stop() {
        ++generation
        try { manager?.unregisterListener() } catch (_: Exception) { }
        try { car?.disconnect() } catch (_: Exception) { }
        manager = null
        car = null
    }
}
