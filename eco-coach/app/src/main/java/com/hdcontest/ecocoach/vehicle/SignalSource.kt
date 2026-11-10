package com.hdcontest.ecocoach.vehicle

import com.hdcontest.ecocoach.core.EcoMode
import com.hdcontest.ecocoach.core.Powertrain

/**
 * 차량 신호 공급원. 실제 차량(Pleos Vehicle SDK + AAOS)과 데모 시나리오를 같은 방식으로 다룬다.
 * 모든 콜백은 메인 스레드에서 호출된다.
 */
interface SignalSource {

    interface Listener {
        fun onPowertrain(powertrain: Powertrain)
        fun onSpeed(tMs: Long, speedKmh: Double)
        fun onGearParked(parked: Boolean)
        fun onBrakePedal(pressed: Boolean)
        fun onDriveMode(mode: EcoMode)
        /** EV: km/kWh, ICE: L/100km */
        fun onInstantEfficiency(value: Float)
        /** 배터리 SoC 또는 연료 잔량(%) */
        fun onEnergyLevel(percent: Int)
        fun onOdometer(km: Float)
        fun onSpeedLimit(kmh: Int?)
        fun onStatus(message: String)
    }

    fun start(listener: Listener)
    fun stop()

    /** 주행 종료 시 차량이 계산한 이번 주행 평균 효율을 읽는다. */
    fun readTripEfficiency(callback: (Float?) -> Unit)
}
