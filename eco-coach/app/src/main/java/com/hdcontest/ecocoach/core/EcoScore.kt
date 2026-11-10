package com.hdcontest.ecocoach.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 주행 통계를 0~100점의 에코 점수로 환산한다.
 *
 * - 급가속/급감속: 거리에 비례한 허용 횟수(기본 2회 + 10km당 3회)를 기준으로 감점
 * - 속도 준수: 주행 시간 중 과속 비율
 * - 부드러움: 주행 시간 중 완만한 가감속(|a| < 0.8 m/s²) 비율
 * - 공회전: 내연기관만 적용, 전체 시간 중 정차 비율
 * - ECO 모드 주행 비율만큼 최대 5점 가산
 */
object EcoScore {

    fun compute(stats: TripStats, powertrain: Powertrain): ScoreBreakdown {
        val allowedEvents = 2.0 + stats.distanceKm * 0.3
        val acceleration = eventScore(stats.hardAccelCount, allowedEvents)
        val braking = eventScore(stats.hardBrakeCount, allowedEvents)

        val movingMs = max(stats.movingMs, 1L).toDouble()
        val speedingRatio = stats.speedingMs / movingMs
        val speedKeeping = clamp(100 - speedingRatio * 300)
        val smoothness = if (stats.movingMs > 0) clamp(stats.smoothMs / movingMs * 100) else 100

        val idling = if (powertrain == Powertrain.ICE) {
            val totalMs = max(stats.durationMs, stats.movingMs + stats.stoppedMs).coerceAtLeast(1L)
            clamp(100 - stats.stoppedMs.toDouble() / totalMs * 200)
        } else {
            null
        }

        val weighted = if (idling != null) {
            acceleration * 0.25 + braking * 0.25 + speedKeeping * 0.2 + smoothness * 0.15 + idling * 0.15
        } else {
            acceleration * 0.3 + braking * 0.3 + speedKeeping * 0.2 + smoothness * 0.2
        }
        val ecoRatio = if (stats.durationMs > 0) stats.ecoModeMs.toDouble() / stats.durationMs else 0.0
        val total = clamp(weighted + ecoRatio * 5)

        return ScoreBreakdown(
            total = total,
            acceleration = acceleration,
            braking = braking,
            speedKeeping = speedKeeping,
            smoothness = smoothness,
            idling = idling,
            grade = grade(total),
            tips = tips(acceleration, braking, speedKeeping, smoothness, idling, stats, powertrain),
        )
    }

    fun grade(total: Int): String = when {
        total >= 90 -> "에코 마스터"
        total >= 75 -> "에코 드라이버"
        total >= 60 -> "보통"
        else -> "개선 필요"
    }

    private fun eventScore(count: Int, allowed: Double): Int =
        clamp(100 * (1 - count / (allowed * 2)))

    private fun tips(
        acceleration: Int,
        braking: Int,
        speedKeeping: Int,
        smoothness: Int,
        idling: Int?,
        stats: TripStats,
        powertrain: Powertrain,
    ): List<String> {
        val candidates = buildList {
            add(acceleration to "출발할 때 5초 정도에 걸쳐 천천히 가속해 보세요. 급가속을 줄이는 것만으로도 에너지 소비가 눈에 띄게 줄어요.")
            add(braking to if (powertrain == Powertrain.EV) {
                "앞차와 거리를 넉넉히 두고 미리 가속 페달에서 발을 떼 보세요. 회생제동으로 배터리도 다시 채울 수 있어요."
            } else {
                "앞차와 거리를 넉넉히 두고 미리 감속해 보세요. 급제동이 줄면 연비와 브레이크 수명이 함께 좋아져요."
            })
            add(speedKeeping to "제한속도를 지키면 공기 저항이 줄어 효율이 좋아져요. 고속도로에서는 크루즈 컨트롤을 활용해 보세요.")
            add(smoothness to "속도를 자주 바꾸기보다 일정하게 유지하는 구간을 늘려 보세요.")
            if (idling != null) add(idling to "정차 시간이 길다면 시동을 끄거나 오토 스탑 기능을 켜 두세요.")
        }
        val tips = candidates.filter { it.first < 85 }.sortedBy { it.first }.take(2).map { it.second }
        if (tips.isNotEmpty()) return tips
        return if (stats.ecoModeMs == 0L && stats.durationMs > 0) {
            listOf("아주 부드럽게 운전하셨어요. 다음에는 ECO 주행 모드도 함께 써 보세요.")
        } else {
            listOf("아주 부드럽게 운전하셨어요. 지금처럼만 운전하시면 돼요!")
        }
    }

    private fun clamp(value: Double): Int = min(100.0, max(0.0, value)).roundToInt()
}
