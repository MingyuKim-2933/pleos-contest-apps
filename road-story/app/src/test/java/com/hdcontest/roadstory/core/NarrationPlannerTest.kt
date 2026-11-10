package com.hdcontest.roadstory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NarrationPlannerTest {

    private val gate = Poi("gate", "숭례문", "문화유산", 37.5600, 126.9753, 400f, "", "이야기")
    private val palace = Poi("palace", "경복궁", "궁궐", 37.5796, 126.9770, 600f, "", "이야기")

    @Test
    fun `반경 밖에서는 안내하지 않는다`() {
        val planner = NarrationPlanner(listOf(gate))
        assertNull(planner.onLocation(37.5547, 126.9707, 0))
    }

    @Test
    fun `반경에 처음 들어오면 한 번만 안내한다`() {
        val planner = NarrationPlanner(listOf(gate))
        assertEquals(gate, planner.onLocation(37.5601, 126.9752, 0))
        assertNull(planner.onLocation(37.5602, 126.9751, 1_000))
    }

    @Test
    fun `쿨다운 중에는 다시 들어와도 안내하지 않는다`() {
        val planner = NarrationPlanner(listOf(gate), cooldownMs = 10_000)
        assertEquals(gate, planner.onLocation(37.5600, 126.9753, 0))
        planner.onLocation(37.5547, 126.9707, 2_000) // 이탈
        assertNull(planner.onLocation(37.5600, 126.9753, 5_000))
    }

    @Test
    fun `쿨다운이 지나면 다시 안내한다`() {
        val planner = NarrationPlanner(listOf(gate), cooldownMs = 10_000)
        assertEquals(gate, planner.onLocation(37.5600, 126.9753, 0))
        planner.onLocation(37.5547, 126.9707, 2_000)
        assertEquals(gate, planner.onLocation(37.5600, 126.9753, 20_000))
    }

    @Test
    fun `근접 알림으로 안내한 장소는 위치 업데이트로 중복 안내하지 않는다`() {
        val planner = NarrationPlanner(listOf(gate))
        assertEquals(gate, planner.onProximityEnter("gate", 0))
        assertNull(planner.onLocation(37.5600, 126.9753, 1_000))
    }

    @Test
    fun `알 수 없는 근접 알림 ID 는 무시한다`() {
        val planner = NarrationPlanner(listOf(gate))
        assertNull(planner.onProximityEnter("unknown", 0))
    }

    @Test
    fun `주변 목록은 가까운 순서`() {
        val planner = NarrationPlanner(listOf(palace, gate))
        val nearby = planner.nearby(37.5547, 126.9707, heading = 0f)
        assertEquals(listOf("gate", "palace"), nearby.map { it.poi.id })
    }
}
