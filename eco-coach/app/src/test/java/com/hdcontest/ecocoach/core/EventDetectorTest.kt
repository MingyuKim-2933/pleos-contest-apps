package com.hdcontest.ecocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetectorTest {

    /** [fromKmh] 에서 [toKmh] 까지 [durationMs] 동안 200ms 간격으로 선형 변화하는 샘플을 넣는다. */
    private fun EventDetector.ramp(
        startMs: Long,
        fromKmh: Double,
        toKmh: Double,
        durationMs: Long,
        limit: Int? = null,
    ): List<DriveEvent> {
        val events = mutableListOf<DriveEvent>()
        var t = 0L
        while (t <= durationMs) {
            val v = fromKmh + (toKmh - fromKmh) * t / durationMs
            events += onSpeed(startMs + t, v, limit)
            t += 200
        }
        return events
    }

    @Test
    fun `완만한 가속은 이벤트가 없다`() {
        val events = EventDetector().ramp(0, 0.0, 40.0, 10_000) // 약 1.1 m/s²
        assertTrue(events.isEmpty())
    }

    @Test
    fun `급가속은 한 번만 감지한다`() {
        val events = EventDetector().ramp(0, 40.0, 75.0, 3_000) // 약 3.2 m/s²
        assertEquals(listOf(DriveEventType.HARD_ACCEL), events.map { it.type })
    }

    @Test
    fun `급감속을 감지한다`() {
        val events = EventDetector().ramp(0, 75.0, 20.0, 4_000) // 약 3.8 m/s²
        assertEquals(listOf(DriveEventType.HARD_BRAKE), events.map { it.type })
        assertTrue(events.first().value < -3.4)
    }

    @Test
    fun `제한속도 + 10km h 초과가 5초 이어지면 과속`() {
        val detector = EventDetector()
        val shortOver = detector.ramp(0, 65.0, 65.0, 4_000, limit = 50)
        assertTrue(shortOver.isEmpty())
        val longOver = detector.ramp(4_200, 65.0, 65.0, 4_000, limit = 50)
        assertEquals(listOf(DriveEventType.SPEEDING), longOver.map { it.type })
        assertEquals(15.0, longOver.first().value, 0.01)
    }

    @Test
    fun `제한속도 정보가 없으면 과속 판정을 하지 않는다`() {
        val events = EventDetector().ramp(0, 120.0, 120.0, 10_000, limit = null)
        assertTrue(events.isEmpty())
    }
}
