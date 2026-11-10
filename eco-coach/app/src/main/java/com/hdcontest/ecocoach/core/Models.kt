package com.hdcontest.ecocoach.core

enum class Powertrain { EV, ICE, UNKNOWN }

enum class EcoMode { ECO, NORMAL, SPORT, UNKNOWN }

enum class DriveEventType { HARD_ACCEL, HARD_BRAKE, SPEEDING }

data class DriveEvent(
    val type: DriveEventType,
    val timestampMs: Long,
    /** 급가속/급감속: 가속도(m/s²), 과속: 초과 속도(km/h) */
    val value: Double,
)

/** 한 번의 주행에서 모은 통계. */
data class TripStats(
    val durationMs: Long = 0,
    val movingMs: Long = 0,
    val stoppedMs: Long = 0,
    val smoothMs: Long = 0,
    val speedingMs: Long = 0,
    val ecoModeMs: Long = 0,
    val sportModeMs: Long = 0,
    val distanceKm: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val hardAccelCount: Int = 0,
    val hardBrakeCount: Int = 0,
    val speedingCount: Int = 0,
    val brakePressCount: Int = 0,
) {
    val avgSpeedKmh: Double
        get() = if (movingMs > 0) distanceKm / (movingMs / 3_600_000.0) else 0.0
}

/** 항목별 점수(0~100). idle 은 내연기관 차량에만 적용한다. */
data class ScoreBreakdown(
    val total: Int,
    val acceleration: Int,
    val braking: Int,
    val speedKeeping: Int,
    val smoothness: Int,
    val idling: Int?,
    val grade: String,
    val tips: List<String>,
)

data class TripReport(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val powertrain: Powertrain,
    val stats: TripStats,
    val score: ScoreBreakdown,
    /** EV: km/kWh, ICE: L/100km. 차량에서 읽지 못하면 null */
    val averageEfficiency: Float?,
    /** 주행 동안 사용한 배터리/연료 (%p) */
    val energyUsedPercent: Int?,
)
