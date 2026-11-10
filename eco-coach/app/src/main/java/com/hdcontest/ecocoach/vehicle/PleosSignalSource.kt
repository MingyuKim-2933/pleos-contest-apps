package com.hdcontest.ecocoach.vehicle

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import android.annotation.SuppressLint
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.hdcontest.ecocoach.core.EcoMode
import com.hdcontest.ecocoach.core.Powertrain
import kotlin.math.abs

/**
 * 실제 차량 신호.
 *
 * | 신호 | 출처 |
 * |---|---|
 * | 차속, 기어 | AAOS CarPropertyManager (PERF_VEHICLE_SPEED, GEAR_SELECTION) |
 * | 브레이크 페달 | Pleos Vehicle SDK · Brake |
 * | 주행 모드(ECO/NORMAL/SPORT) | Pleos Vehicle SDK · DrivingMode |
 * | 순간/평균 전비·연비, 잔량 | Pleos Vehicle SDK · EvBattery / Fuel |
 * | 누적 주행거리 | Pleos Vehicle SDK · Odometer |
 * | 도로 제한속도 | Pleos NaviHelper SDK · onCurrentRoadSpeedLimit |
 *
 * Pleos SDK 정책상 AAOS 표준 프로퍼티가 있는 항목(차속, 기어)은 표준 API 를 사용한다.
 */
class PleosSignalSource(context: Context) : SignalSource {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: SignalSource.Listener? = null

    private var vehicle: Vehicle? = null
    private var powertrain = Powertrain.UNKNOWN
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var naviHelper: NaviHelper? = null

    // region Pleos Vehicle SDK 리스너

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            status?.let { post { onBrakePedal(it) } }
        }

        override fun onFailed(e: Exception) = logFailure("brake", e)
    }

    private val driveModeListener = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(state: DrivingModeType?) {
            post { onDriveMode(state.toEcoMode()) }
        }

        override fun onFailed(e: Exception) = logFailure("drivingMode", e)
    }

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            value?.let { post { onOdometer(it) } }
        }

        override fun onFailed(e: Exception) = logFailure("odometer", e)
    }

    private val evEfficiencyListener = object : EvBatteryEfficiencyListener {
        override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) {
            efficiency?.let { post { onInstantEfficiency(it) } }
        }

        override fun onFailed(e: Exception) = logFailure("evEfficiency", e)
    }

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            value?.let { post { onEnergyLevel(it) } }
        }

        override fun onFailed(e: Exception) = logFailure("soc", e)
    }

    private val fuelEconomyListener = object : FuelEconomyListener {
        override fun onFuelEconomyUpdated(efficiency: Float?) {
            efficiency?.let { post { onInstantEfficiency(it) } }
        }

        override fun onFailed(e: Exception) = logFailure("fuelEconomy", e)
    }

    private val fuelPercentListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(value: Int?) {
            value?.let { post { onEnergyLevel(it) } }
        }

        override fun onFailed(e: Exception) = logFailure("fuelPercent", e)
    }

    // endregion

    // region AAOS 표준 프로퍼티 콜백

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    // m/s, 후진 시 음수일 수 있다
                    val mps = (value.value as? Float) ?: return
                    val kmh = abs(mps) * 3.6
                    post { onSpeed(SystemClock.elapsedRealtime(), kmh) }
                }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val gear = (value.value as? Int) ?: return
                    post { onGearParked(gear == GEAR_PARK) }
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "property error: $propertyId")
        }
    }

    // endregion

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            post { onSpeedLimit(speedLimit.takeIf { it > 0 }) }
        }
    }

    override fun start(listener: SignalSource.Listener) {
        stop()
        this.listener = listener
        startVehicleSdk()
        startCarProperties()
        startNavi()
    }

    @SuppressLint("MissingPermission")
    private fun startVehicleSdk() {
        val v = Vehicle(appContext)
        vehicle = v
        v.initialize()

        v.getBrake().registerBrakePedalStatus(brakeListener)
        v.getDrivingMode().run {
            getCurrentDrivingModeState(
                onComplete = { post { onDriveMode(it.toEcoMode()) } },
                onFailed = { logFailure("drivingMode", it) },
            )
            registerDrivingModeState(driveModeListener)
        }
        v.getOdometer().run {
            getCurrentDistanceDriven(
                onComplete = { km -> km?.let { post { onOdometer(it) } } },
                onFailed = { logFailure("odometer", it) },
            )
            registerDistanceDriven(odometerListener)
        }
        detectPowertrain(v, retry = true)
    }

    /** EV 배터리 SoC 를 읽을 수 있으면 EV, 아니면 연료 잔량으로 내연기관 여부를 판단한다. */
    @SuppressLint("MissingPermission")
    private fun detectPowertrain(v: Vehicle, retry: Boolean) {
        v.getEvBattery().getEvBatteryStateOfCharge(
            onComplete = { soc ->
                if (soc != null) {
                    post { onEnergyLevel(soc) }
                    usePowertrain(v, Powertrain.EV)
                } else {
                    detectFuel(v)
                }
            },
            onFailed = { e ->
                if (retry) {
                    // 서비스 바인딩 직후에는 연결 전일 수 있어 1초 뒤 한 번 더 시도한다.
                    // (Vehicle SDK 2.3.2 배포본은 VehicleException/ErrorCode 가 공개되지 않아 원인 구분 없이 재시도)
                    logFailure("evSoc", e)
                    main.postDelayed({ vehicle?.let { detectPowertrain(it, retry = false) } }, 1_000)
                } else {
                    detectFuel(v)
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun detectFuel(v: Vehicle) {
        v.getFuel().getFuelPercent(
            onComplete = { percent ->
                percent?.let { post { onEnergyLevel(it) } }
                usePowertrain(v, if (percent != null) Powertrain.ICE else Powertrain.UNKNOWN)
            },
            onFailed = {
                logFailure("fuelPercent", it)
                usePowertrain(v, Powertrain.UNKNOWN)
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun usePowertrain(v: Vehicle, type: Powertrain) {
        main.post {
            if (vehicle !== v) return@post
            powertrain = type
            listener?.onPowertrain(type)
            when (type) {
                Powertrain.EV -> v.getEvBattery().run {
                    registerInstantaneousEvEfficiency(evEfficiencyListener)
                    registerEvBatteryStateOfCharge(socListener)
                }
                Powertrain.ICE -> v.getFuel().run {
                    registerInstantaneousFuelEconomy(fuelEconomyListener)
                    registerFuelPercent(fuelPercentListener)
                }
                Powertrain.UNKNOWN -> Unit
            }
        }
    }

    // subscribePropertyEvents() 는 Android 15(API 35) 차량에서만 쓸 수 있어 minSdk 28 호환용 API 를 유지한다.
    @Suppress("DEPRECATION")
    private fun startCarProperties() {
        val granted = ContextCompat.checkSelfPermission(appContext, PERMISSION_CAR_SPEED) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            listener?.onStatus("차속 권한이 없어 급가속·급제동을 감지할 수 없어요.")
            return
        }
        val c = Car.createCar(appContext) ?: run {
            listener?.onStatus("차량 서비스에 연결할 수 없어요.")
            return
        }
        car = c
        val manager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        propertyManager = manager
        manager.registerCallback(
            propertyCallback,
            VehiclePropertyIds.PERF_VEHICLE_SPEED,
            CarPropertyManager.SENSOR_RATE_FAST,
        )
        manager.registerCallback(
            propertyCallback,
            VehiclePropertyIds.GEAR_SELECTION,
            CarPropertyManager.SENSOR_RATE_ONCHANGE,
        )
    }

    private fun startNavi() {
        val helper = NaviHelper(appContext)
        naviHelper = helper
        helper.initialize(
            onConnected = { main.post { if (naviHelper === helper) helper.addListener(naviListener) } },
            onFailed = { Log.w(TAG, "NaviHelper unavailable; speed limit disabled", it) },
        )
    }

    @SuppressLint("MissingPermission")
    override fun readTripEfficiency(callback: (Float?) -> Unit) {
        val v = vehicle ?: return callback(null)
        val deliver: (Float?) -> Unit = { value -> main.post { callback(value?.takeIf { it > 0f }) } }
        val onFailed: (Exception) -> Unit = { e ->
            logFailure("tripEfficiency", e)
            deliver(null)
        }
        when (powertrain) {
            Powertrain.EV -> v.getEvBattery().getCurrentEvEfficiency(onComplete = deliver, onFailed = onFailed)
            Powertrain.ICE -> v.getFuel().getCurrentFuelEconomy(onComplete = deliver, onFailed = onFailed)
            Powertrain.UNKNOWN -> callback(null)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun stop() {
        vehicle?.let { v ->
            runCatching {
                v.getBrake().unregisterBrakePedalStatus(brakeListener)
                v.getDrivingMode().unregisterDrivingModeState(driveModeListener)
                v.getOdometer().unregisterDistanceDriven(odometerListener)
                when (powertrain) {
                    Powertrain.EV -> v.getEvBattery().run {
                        unregisterInstantaneousEvEfficiency(evEfficiencyListener)
                        unregisterEvBatteryStateOfCharge(socListener)
                    }
                    Powertrain.ICE -> v.getFuel().run {
                        unregisterInstantaneousFuelEconomy(fuelEconomyListener)
                        unregisterFuelPercent(fuelPercentListener)
                    }
                    Powertrain.UNKNOWN -> Unit
                }
            }.onFailure { Log.w(TAG, "unregister failed", it) }
            v.release()
        }
        vehicle = null
        powertrain = Powertrain.UNKNOWN

        propertyManager?.unregisterCallback(propertyCallback)
        propertyManager = null
        car?.disconnect()
        car = null

        naviHelper?.run {
            removeListener(naviListener)
            release()
        }
        naviHelper = null
        listener = null
    }

    private fun post(block: SignalSource.Listener.() -> Unit) {
        main.post { listener?.block() }
    }

    private fun logFailure(what: String, e: Exception) {
        Log.w(TAG, "$what failed: ${e.javaClass.simpleName} ${e.message}")
    }

    private fun DrivingModeType?.toEcoMode() = when (this) {
        DrivingModeType.ECO -> EcoMode.ECO
        DrivingModeType.NORMAL -> EcoMode.NORMAL
        DrivingModeType.SPORT -> EcoMode.SPORT
        null -> EcoMode.UNKNOWN
    }

    private companion object {
        const val TAG = "PleosSignalSource"
        const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"

        /** android.car.VehicleGear.GEAR_PARK */
        const val GEAR_PARK = 0x0004
    }
}
