package com.hdcontest.ecocoach.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hdcontest.ecocoach.core.EcoMode
import com.hdcontest.ecocoach.core.Powertrain

/**
 * 시연용 가상 주행 (약 2분 45초, 전기차 기준).
 *
 * 부드러운 출발 → 급가속·과속 → 급제동 → 정차 → ECO 모드 정속 주행 → 주차 순서로 진행되어
 * 실시간 음성 코칭과 주행 리포트를 한 번에 보여줄 수 있다.
 */
class DemoSignalSource(private val tickMs: Long = 200L) : SignalSource {

    private data class Segment(
        val durationMs: Long,
        val targetKmh: Double,
        val limitKmh: Int?,
        val brake: Boolean = false,
        val mode: EcoMode = EcoMode.NORMAL,
        val parked: Boolean = false,
    )

    // 음성 코칭 간격(10초)보다 이벤트 간격을 넓게 잡아 급가속 → 과속 → 급제동 안내를 모두 들을 수 있다.
    private val script = listOf(
        Segment(2_000, 0.0, null, parked = true),
        Segment(10_000, 40.0, 50),              // 부드러운 출발 (약 1.1 m/s²)
        Segment(8_000, 40.0, 50),
        Segment(3_000, 75.0, 80),               // 급가속 (약 3.2 m/s²)
        Segment(6_000, 75.0, 80),
        Segment(10_000, 75.0, 50),              // 제한속도 50 구간 진입 → 과속
        Segment(6_000, 55.0, 50),               // 완만한 감속
        Segment(4_000, 55.0, 50),
        Segment(3_500, 10.0, 50, brake = true), // 급제동 (약 3.6 m/s²)
        Segment(3_000, 0.0, 50, brake = true),
        Segment(6_000, 0.0, 50, brake = true),  // 신호 대기
        Segment(12_000, 50.0, 60, mode = EcoMode.ECO),
        Segment(70_000, 50.0, 60, mode = EcoMode.ECO),  // ECO 정속 주행으로 점수 회복
        Segment(10_000, 0.0, 60, brake = true, mode = EcoMode.ECO),
        Segment(2_000, 0.0, 60, brake = true, mode = EcoMode.ECO),
        Segment(8_000, 0.0, null, mode = EcoMode.ECO, parked = true),
    )

    private val handler = Handler(Looper.getMainLooper())
    private var listener: SignalSource.Listener? = null
    private var segmentIndex = 0
    private var segmentElapsed = 0L
    private var segmentStartKmh = 0.0
    private var speedKmh = 0.0
    private var odometerKm = 12_345.0f
    private var soc = 78.0
    private var lastBrake: Boolean? = null
    private var lastMode: EcoMode? = null
    private var lastParked: Boolean? = null
    private var lastLimit: Int? = -1

    private val tick = object : Runnable {
        override fun run() {
            if (step()) handler.postDelayed(this, tickMs)
        }
    }

    override fun start(listener: SignalSource.Listener) {
        stop()
        this.listener = listener
        segmentIndex = 0
        segmentElapsed = 0
        segmentStartKmh = 0.0
        speedKmh = 0.0
        lastBrake = null
        lastMode = null
        lastParked = null
        lastLimit = -1
        listener.onPowertrain(Powertrain.EV)
        listener.onEnergyLevel(soc.toInt())
        listener.onOdometer(odometerKm)
        listener.onStatus("데모 주행 중이에요.")
        handler.post(tick)
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        listener = null
    }

    override fun readTripEfficiency(callback: (Float?) -> Unit) = callback(5.8f)

    /** 한 틱 진행. 시나리오가 끝나면 false. */
    private fun step(): Boolean {
        val l = listener ?: return false
        val segment = script.getOrNull(segmentIndex) ?: run {
            l.onStatus("데모 주행이 끝났어요.")
            return false
        }

        segmentElapsed += tickMs
        val fraction = (segmentElapsed.toDouble() / segment.durationMs).coerceIn(0.0, 1.0)
        val previousKmh = speedKmh
        speedKmh = segmentStartKmh + (segment.targetKmh - segmentStartKmh) * fraction

        val distanceKm = (previousKmh + speedKmh) / 2.0 * tickMs / 3_600_000.0
        odometerKm += distanceKm.toFloat()
        val accel = ((speedKmh - previousKmh) / 3.6) / (tickMs / 1000.0)
        // 가속할수록 전비가 나빠지고, 감속 시에는 회생제동으로 좋아지는 단순 모델
        val efficiency = when {
            speedKmh < 1 -> 0.0
            accel > 0.3 -> (7.0 - accel * 1.4).coerceAtLeast(1.5)
            accel < -0.3 -> 9.5
            else -> 7.0 - (speedKmh - 50).coerceAtLeast(0.0) * 0.05
        }
        soc -= distanceKm / 3.0

        if (segment.parked != lastParked) l.onGearParked(segment.parked).also { lastParked = segment.parked }
        if (segment.mode != lastMode) l.onDriveMode(segment.mode).also { lastMode = segment.mode }
        if (segment.brake != lastBrake) l.onBrakePedal(segment.brake).also { lastBrake = segment.brake }
        if (segment.limitKmh != lastLimit) l.onSpeedLimit(segment.limitKmh).also { lastLimit = segment.limitKmh }
        l.onSpeed(SystemClock.elapsedRealtime(), speedKmh)
        if (efficiency > 0) l.onInstantEfficiency(efficiency.toFloat())
        l.onOdometer(odometerKm)
        l.onEnergyLevel(soc.toInt())

        if (segmentElapsed >= segment.durationMs) {
            segmentIndex++
            segmentElapsed = 0
            segmentStartKmh = speedKmh
        }
        return true
    }
}
