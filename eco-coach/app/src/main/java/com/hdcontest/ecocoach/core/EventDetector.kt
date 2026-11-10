package com.hdcontest.ecocoach.core

/**
 * 차속 샘플에서 급가속 / 급감속 / 과속을 감지한다.
 *
 * 가속도는 약 1초 구간의 속도 변화로 계산해 센서 노이즈를 줄인다.
 * 기준값은 국내 운행기록 분석에서 흔히 쓰는 급가속(약 10km/h/s), 급감속(약 12km/h/s)에 맞췄다.
 */
class EventDetector(
    private val hardAccelMps2: Double = 2.8,
    private val hardBrakeMps2: Double = -3.4,
    private val speedingMarginKmh: Double = 10.0,
    private val speedingHoldMs: Long = 5_000,
    private val sameEventGapMs: Long = 3_000,
    private val windowMs: Long = 1_000,
) {
    private data class Sample(val tMs: Long, val kmh: Double)

    private val samples = ArrayDeque<Sample>()
    private var lastAccelEventMs = Long.MIN_VALUE / 2
    private var lastBrakeEventMs = Long.MIN_VALUE / 2
    private var inAccelEpisode = false
    private var inBrakeEpisode = false
    private var overLimitSinceMs: Long? = null
    private var speedingReported = false

    /** 최근 1초 구간 가속도(m/s²). 샘플이 부족하면 null */
    var lastAccelerationMps2: Double? = null
        private set

    fun isSpeeding(speedKmh: Double, limitKmh: Int?): Boolean =
        limitKmh != null && limitKmh > 0 && speedKmh > limitKmh + speedingMarginKmh

    fun onSpeed(tMs: Long, speedKmh: Double, limitKmh: Int?): List<DriveEvent> {
        val events = mutableListOf<DriveEvent>()
        samples.addLast(Sample(tMs, speedKmh))
        while (samples.size > 2 && tMs - samples[1].tMs >= windowMs) samples.removeFirst()

        val oldest = samples.first()
        val dt = (tMs - oldest.tMs) / 1000.0
        lastAccelerationMps2 = if (dt >= windowMs / 2000.0) {
            ((speedKmh - oldest.kmh) / 3.6) / dt
        } else {
            null
        }

        // 기준을 넘은 상태가 이어지는 동안은 한 번의 이벤트로 본다.
        // 가속도가 기준의 절반 아래로 내려와야 구간이 끝난다.
        lastAccelerationMps2?.let { accel ->
            if (accel >= hardAccelMps2) {
                if (!inAccelEpisode && tMs - lastAccelEventMs >= sameEventGapMs) {
                    lastAccelEventMs = tMs
                    events += DriveEvent(DriveEventType.HARD_ACCEL, tMs, accel)
                }
                inAccelEpisode = true
            } else if (accel < hardAccelMps2 / 2) {
                inAccelEpisode = false
            }

            if (accel <= hardBrakeMps2) {
                if (!inBrakeEpisode && tMs - lastBrakeEventMs >= sameEventGapMs) {
                    lastBrakeEventMs = tMs
                    events += DriveEvent(DriveEventType.HARD_BRAKE, tMs, accel)
                }
                inBrakeEpisode = true
            } else if (accel > hardBrakeMps2 / 2) {
                inBrakeEpisode = false
            }
        }

        if (isSpeeding(speedKmh, limitKmh)) {
            val since = overLimitSinceMs ?: tMs.also { overLimitSinceMs = it }
            if (!speedingReported && tMs - since >= speedingHoldMs) {
                speedingReported = true
                events += DriveEvent(DriveEventType.SPEEDING, tMs, speedKmh - limitKmh!!)
            }
        } else {
            overLimitSinceMs = null
            speedingReported = false
        }
        return events
    }
}
