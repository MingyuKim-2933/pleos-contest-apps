package com.hdcontest.ecocoach.core

import kotlin.math.abs
import kotlin.math.max

/**
 * 주행 중 들어오는 신호를 모아 [TripStats] 를 만든다. 순수 로직이라 단위 테스트로 검증한다.
 */
class TripAccumulator(
    private val startMs: Long,
    private val detector: EventDetector = EventDetector(),
    private val movingThresholdKmh: Double = 1.0,
    private val smoothAccelMps2: Double = 0.8,
) {
    private var stats = TripStats()
    private var lastSpeedT: Long? = null
    private var lastSpeedKmh = 0.0
    private var lastSpeedLimit: Int? = null
    private var lastModeT: Long = startMs
    private var mode = EcoMode.UNKNOWN
    private var brakePressed = false
    private var odometerStartKm: Float? = null
    private var odometerLatestKm: Float? = null

    val currentStats: TripStats get() = stats

    fun onSpeed(tMs: Long, speedKmh: Double, limitKmh: Int?): List<DriveEvent> {
        val prevT = lastSpeedT
        if (prevT != null && tMs > prevT) {
            val dtMs = tMs - prevT
            // 직전 구간은 직전 상태로 집계 (사다리꼴 적분으로 거리 계산)
            val avgKmh = (lastSpeedKmh + speedKmh) / 2.0
            val moving = lastSpeedKmh > movingThresholdKmh || speedKmh > movingThresholdKmh
            val accel = detector.lastAccelerationMps2
            stats = stats.copy(
                distanceKm = stats.distanceKm + avgKmh * dtMs / 3_600_000.0,
                movingMs = stats.movingMs + if (moving) dtMs else 0,
                stoppedMs = stats.stoppedMs + if (moving) 0 else dtMs,
                smoothMs = stats.smoothMs +
                    if (moving && accel != null && abs(accel) < smoothAccelMps2) dtMs else 0,
                speedingMs = stats.speedingMs + if (detector.isSpeeding(lastSpeedKmh, lastSpeedLimit)) dtMs else 0,
            )
        }
        val events = detector.onSpeed(tMs, speedKmh, limitKmh)
        events.forEach { event ->
            stats = when (event.type) {
                DriveEventType.HARD_ACCEL -> stats.copy(hardAccelCount = stats.hardAccelCount + 1)
                DriveEventType.HARD_BRAKE -> stats.copy(hardBrakeCount = stats.hardBrakeCount + 1)
                DriveEventType.SPEEDING -> stats.copy(speedingCount = stats.speedingCount + 1)
            }
        }
        stats = stats.copy(maxSpeedKmh = max(stats.maxSpeedKmh, speedKmh))
        lastSpeedT = tMs
        lastSpeedKmh = speedKmh
        lastSpeedLimit = limitKmh
        return events
    }

    /** 브레이크 페달 상태. 눌림으로 바뀐 횟수를 센다. */
    fun onBrakePedal(pressed: Boolean) {
        if (pressed && !brakePressed) stats = stats.copy(brakePressCount = stats.brakePressCount + 1)
        brakePressed = pressed
    }

    fun onDriveMode(tMs: Long, newMode: EcoMode) {
        closeModeSegment(tMs)
        mode = newMode
    }

    /**
     * 차량 누적 주행거리(km). 계기판 분해능이 거칠 수 있어 차속 적분값을 우선 쓰고,
     * 차속 신호가 없을 때만 시작/종료 값 차이로 거리를 계산한다.
     */
    fun onOdometer(km: Float) {
        if (odometerStartKm == null) odometerStartKm = km
        odometerLatestKm = km
    }

    fun finish(endMs: Long): TripStats {
        closeModeSegment(endMs)
        val odometerDelta = odometerStartKm?.let { start -> odometerLatestKm?.minus(start) }
        val distance = if (stats.movingMs == 0L && odometerDelta != null && odometerDelta > 0f) {
            odometerDelta.toDouble()
        } else {
            stats.distanceKm
        }
        stats = stats.copy(durationMs = endMs - startMs, distanceKm = distance)
        return stats
    }

    fun elapsed(nowMs: Long) = nowMs - startMs

    private fun closeModeSegment(tMs: Long) {
        val dt = (tMs - lastModeT).coerceAtLeast(0)
        stats = when (mode) {
            EcoMode.ECO -> stats.copy(ecoModeMs = stats.ecoModeMs + dt)
            EcoMode.SPORT -> stats.copy(sportModeMs = stats.sportModeMs + dt)
            else -> stats
        }
        lastModeT = tMs
    }
}
