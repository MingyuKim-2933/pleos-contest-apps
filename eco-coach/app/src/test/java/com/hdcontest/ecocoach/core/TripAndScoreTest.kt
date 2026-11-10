package com.hdcontest.ecocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAndScoreTest {

    @Test
    fun `60km h 로 1분 달리면 약 1km`() {
        val acc = TripAccumulator(startMs = 0)
        var t = 0L
        while (t <= 60_000) {
            acc.onSpeed(t, 60.0, null)
            t += 200
        }
        val stats = acc.finish(60_000)
        assertEquals(1.0, stats.distanceKm, 0.01)
        assertEquals(60_000, stats.movingMs)
        assertEquals(60_000, stats.durationMs)
    }

    @Test
    fun `브레이크는 눌림으로 바뀐 횟수만 센다`() {
        val acc = TripAccumulator(startMs = 0)
        listOf(true, true, false, true, false, false).forEach(acc::onBrakePedal)
        assertEquals(2, acc.finish(1_000).brakePressCount)
    }

    @Test
    fun `ECO 모드 주행 시간을 집계한다`() {
        val acc = TripAccumulator(startMs = 0)
        acc.onDriveMode(0, EcoMode.NORMAL)
        acc.onDriveMode(10_000, EcoMode.ECO)
        val stats = acc.finish(40_000)
        assertEquals(30_000, stats.ecoModeMs)
    }

    @Test
    fun `차속 신호가 없을 때만 적산거리 차이를 쓴다`() {
        val acc = TripAccumulator(startMs = 0)
        acc.onOdometer(100.0f)
        acc.onOdometer(103.5f)
        assertEquals(3.5, acc.finish(1_000).distanceKm, 0.001)
    }

    @Test
    fun `이벤트 없는 주행은 높은 점수`() {
        val stats = TripStats(
            durationMs = 600_000, movingMs = 600_000, smoothMs = 570_000, distanceKm = 8.0,
        )
        val score = EcoScore.compute(stats, Powertrain.EV)
        assertTrue(score.total >= 95)
        assertEquals("에코 마스터", score.grade)
        assertNull(score.idling)
    }

    @Test
    fun `급가속과 급제동이 잦으면 점수가 낮아지고 관련 팁을 준다`() {
        val stats = TripStats(
            durationMs = 600_000, movingMs = 600_000, smoothMs = 300_000, distanceKm = 5.0,
            hardAccelCount = 6, hardBrakeCount = 5,
        )
        val score = EcoScore.compute(stats, Powertrain.EV)
        assertTrue("total=${score.total}", score.total < 60)
        assertTrue("acceleration=${score.acceleration}", score.acceleration < 20)
        assertTrue(score.tips.any { it.contains("가속") })
    }

    @Test
    fun `내연기관은 공회전 점수를 포함한다`() {
        val stats = TripStats(durationMs = 600_000, movingMs = 400_000, stoppedMs = 200_000, smoothMs = 400_000, distanceKm = 4.0)
        val score = EcoScore.compute(stats, Powertrain.ICE)
        assertNotNull(score.idling)
        assertEquals(33, score.idling)
    }

    @Test
    fun `코칭은 최소 간격과 종류별 횟수를 지킨다`() {
        val policy = CoachPolicy(globalGapMs = 10_000, sameTypeGapMs = 60_000, maxSameTypePerTrip = 2)
        policy.reset(0)
        val accel = { t: Long -> DriveEvent(DriveEventType.HARD_ACCEL, t, 3.0) }
        val brake = { t: Long -> DriveEvent(DriveEventType.HARD_BRAKE, t, -4.0) }
        assertNotNull(policy.onEvent(accel(1_000)))
        assertNull(policy.onEvent(brake(5_000)))      // 전체 간격(10초) 미달
        assertNotNull(policy.onEvent(brake(12_000)))  // 다른 종류는 10초 뒤 허용
        assertNull(policy.onEvent(accel(30_000)))     // 같은 종류 간격(60초) 미달
        assertNotNull(policy.onEvent(accel(70_000)))
        assertNull(policy.onEvent(accel(200_000)))    // 종류별 2회 초과
    }

    @Test
    fun `10분간 이벤트가 없으면 칭찬한다`() {
        val policy = CoachPolicy(praiseAfterMs = 600_000)
        policy.reset(0)
        assertNull(policy.onTick(300_000))
        val praise = policy.onTick(600_000)
        assertNotNull(praise)
        assertTrue(praise!!.startsWith("10분"))
        assertNull(policy.onTick(610_000))
    }
}
