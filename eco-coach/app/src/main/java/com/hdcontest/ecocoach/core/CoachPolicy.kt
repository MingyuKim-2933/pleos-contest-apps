package com.hdcontest.ecocoach.core

import kotlin.math.roundToInt

/**
 * 주행 중 음성 코칭 문장을 고른다.
 *
 * 운전자 주의 분산을 줄이기 위해
 * - 어떤 음성이든 사이에 최소 [globalGapMs] 간격을 두고,
 * - 같은 종류의 지적은 [sameTypeGapMs] 간격, 주행당 [maxSameTypePerTrip] 회까지만 하며,
 * - 이벤트 없이 [praiseAfterMs] 동안 운전하면 한 번씩 칭찬한다.
 */
class CoachPolicy(
    private val globalGapMs: Long = 10_000,
    private val sameTypeGapMs: Long = 60_000,
    private val maxSameTypePerTrip: Int = 2,
    private val praiseAfterMs: Long = 10 * 60_000,
) {
    private var lastSpokenMs: Long? = null
    private var lastEventMs: Long? = null
    private var lastPraiseMs: Long? = null
    private var tripStartMs: Long = 0
    private val spokenCount = mutableMapOf<DriveEventType, Int>()
    private val lastSpokenByType = mutableMapOf<DriveEventType, Long>()

    fun reset(tripStartMs: Long) {
        this.tripStartMs = tripStartMs
        lastSpokenMs = null
        lastEventMs = null
        lastPraiseMs = null
        spokenCount.clear()
        lastSpokenByType.clear()
    }

    fun onEvent(event: DriveEvent): String? {
        val now = event.timestampMs
        lastEventMs = now
        if (!gapPassed(now)) return null
        if (lastSpokenByType[event.type]?.let { now - it < sameTypeGapMs } == true) return null
        val count = spokenCount[event.type] ?: 0
        if (count >= maxSameTypePerTrip) return null
        spokenCount[event.type] = count + 1
        lastSpokenByType[event.type] = now
        lastSpokenMs = now
        return when (event.type) {
            DriveEventType.HARD_ACCEL -> "급가속이 감지됐어요. 조금 더 부드럽게 가속해 보세요."
            DriveEventType.HARD_BRAKE -> "급제동이 있었어요. 앞차와 거리를 조금 더 두면 좋아요."
            DriveEventType.SPEEDING -> "제한속도보다 ${event.value.roundToInt()}킬로미터 빠르게 달리고 있어요."
        }
    }

    /** 주기적으로 호출. 칭찬할 때가 되면 문장을 반환한다. */
    fun onTick(nowMs: Long): String? {
        val calmSince = maxOf(lastEventMs ?: tripStartMs, lastPraiseMs ?: tripStartMs)
        if (nowMs - calmSince < praiseAfterMs || !gapPassed(nowMs)) return null
        lastPraiseMs = nowMs
        lastSpokenMs = nowMs
        val minutes = ((nowMs - calmSince) / 60_000.0).roundToInt()
        return "${minutes}분 동안 급가속이나 급제동 없이 운전하셨어요. 아주 좋아요!"
    }

    private fun gapPassed(nowMs: Long) = lastSpokenMs?.let { nowMs - it >= globalGapMs } ?: true

    companion object {
        fun tripSummary(report: TripReport): String {
            val km = (report.stats.distanceKm * 10).roundToInt() / 10.0
            val tip = report.score.tips.firstOrNull().orEmpty()
            return "주행을 마쳤어요. ${km}킬로미터를 달렸고, 에코 점수는 ${report.score.total}점, " +
                "${report.score.grade} 등급이에요. $tip"
        }
    }
}
