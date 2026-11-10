package com.hdcontest.roadstory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NarrationPlannerTest {

    private val gate = Poi("gate", "숭례문", "문화유산", 37.5600, 126.9753, 400f, "", "이야기")
    private val palace = Poi("palace", "경복궁", "궁궐", 37.5796, 126.9770, 600f, "", "이야기")

    @Test
    fun `반경 밖에서는 안내하지 않는다`() {
        val planner = NarrationPlanner(listOf(gate), trigger = TriggerDistance.RADIUS_ONLY)
        assertNull(planner.onLocation(37.5547, 126.9707, 0))
    }

    @Test
    fun `반경에 처음 들어오면 한 번만 안내한다`() {
        val planner = NarrationPlanner(listOf(gate), trigger = TriggerDistance.RADIUS_ONLY)
        assertEquals(gate, planner.onLocation(37.5601, 126.9752, 0))
        assertNull(planner.onLocation(37.5602, 126.9751, 1_000))
    }

    @Test
    fun `쿨다운 중에는 다시 들어와도 안내하지 않는다`() {
        val planner = NarrationPlanner(listOf(gate), cooldownMs = 10_000, trigger = TriggerDistance.RADIUS_ONLY)
        assertEquals(gate, planner.onLocation(37.5600, 126.9753, 0))
        planner.onLocation(37.5547, 126.9707, 2_000) // 이탈
        assertNull(planner.onLocation(37.5600, 126.9753, 5_000))
    }

    @Test
    fun `쿨다운이 지나면 다시 안내한다`() {
        val planner = NarrationPlanner(listOf(gate), cooldownMs = 10_000, trigger = TriggerDistance.RADIUS_ONLY)
        assertEquals(gate, planner.onLocation(37.5600, 126.9753, 0))
        planner.onLocation(37.5547, 126.9707, 2_000)
        assertEquals(gate, planner.onLocation(37.5600, 126.9753, 20_000))
    }

    @Test
    fun `근접 알림으로 안내한 장소는 위치 업데이트로 중복 안내하지 않는다`() {
        val planner = NarrationPlanner(listOf(gate), trigger = TriggerDistance.RADIUS_ONLY)
        assertEquals(gate, planner.onProximityEnter("gate", 0))
        assertNull(planner.onLocation(37.5600, 126.9753, 1_000))
    }

    @Test
    fun `알 수 없는 근접 알림 ID 는 무시한다`() {
        val planner = NarrationPlanner(listOf(gate), trigger = TriggerDistance.RADIUS_ONLY)
        assertNull(planner.onProximityEnter("unknown", 0))
    }

    @Test
    fun `주변 목록은 가까운 순서`() {
        val planner = NarrationPlanner(listOf(palace, gate))
        val nearby = planner.nearby(37.5547, 126.9707, heading = 0f)
        assertEquals(listOf("gate", "palace"), nearby.map { it.poi.id })
    }

    // region 안내 거리

    private val origin = GeoPoint(37.5000, 127.0000)

    /** 북쪽(0도)으로 [mps] 속도로 달리는 차량을 흉내 낸다. */
    private fun drive(planner: NarrationPlanner, mps: Double, seconds: Int, onStep: (Double, Poi?) -> Unit = { _, _ -> }) {
        for (t in 0..seconds) {
            val lat = origin.latitude + (mps * t) / 111_000.0
            onStep(lat, planner.onLocation(lat, origin.longitude, t * 1_000L, heading = 0f))
        }
    }

    @Test
    fun `정차 중에도 최소 800m 앞에서 안내한다`() {
        val poi = Poi("p", "명소", "", origin.latitude + 790 / 111_000.0, origin.longitude, 100f, "", "")
        val planner = NarrationPlanner(listOf(poi))
        assertEquals(poi, planner.onLocation(origin.latitude, origin.longitude, 0, heading = 0f))
    }

    @Test
    fun `시속 100km 에서는 약 1250m 앞에서 안내를 시작한다`() {
        val poi = Poi("p", "명소", "", origin.latitude + 5_000 / 111_000.0, origin.longitude, 100f, "", "")
        val planner = NarrationPlanner(listOf(poi))
        var remaining: Double? = null
        drive(planner, mps = 27.8, seconds = 200) { lat, hit ->
            if (hit != null && remaining == null) remaining = (poi.latitude - lat) * 111_000.0
        }
        val r = remaining!!
        assert(r in 1_150.0..1_300.0) { "남은 거리 $r" }
        // 안내(약 20초) + 응답(약 6초) 뒤에도 15초 이상 여유가 남는다
        assert(r / 27.8 >= 41) { "여유 ${r / 27.8}초" }
    }

    @Test
    fun `이미 지나쳐 뒤에 있는 장소는 안내하지 않는다`() {
        val behind = Poi("b", "지나친 곳", "", origin.latitude - 600 / 111_000.0, origin.longitude, 100f, "", "")
        val planner = NarrationPlanner(listOf(behind))
        assertNull(planner.onLocation(origin.latitude, origin.longitude, 0, heading = 0f))
    }

    @Test
    fun `여러 곳이 함께 들어오면 대표 명소를 먼저 안내한다`() {
        val near = Poi("near", "동네 명소", "", origin.latitude + 300 / 111_000.0, origin.longitude, 100f, "", "", Poi.LISTED)
        val famous = Poi("famous", "대표 명소", "", origin.latitude + 700 / 111_000.0, origin.longitude, 100f, "", "", Poi.CURATED)
        val planner = NarrationPlanner(listOf(near, famous))
        assertEquals(famous, planner.onLocation(origin.latitude, origin.longitude, 0, heading = 0f))
    }

    // endregion
}
