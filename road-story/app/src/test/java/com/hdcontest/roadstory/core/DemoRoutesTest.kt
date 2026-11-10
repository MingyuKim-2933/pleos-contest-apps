package com.hdcontest.roadstory.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoRoutesTest {

    @Test
    fun `차량 위치를 모르면 서울 경로`() {
        assertEquals(DemoRoutes.SEOUL, DemoRoutes.forVehicle(null))
    }

    @Test
    fun `에뮬레이터 기본 위치(성남)에서는 판교 경로를 고르고 차량 위치에서 출발한다`() {
        val vehicle = GeoPoint(37.41272, 127.09468)
        val route = DemoRoutes.forVehicle(vehicle)
        assertEquals(DemoRoutes.SEONGNAM.name, route.name)
        assertEquals(vehicle, route.points.first())
    }

    @Test
    fun `서울역 근처에서는 서울 경로`() {
        val route = DemoRoutes.forVehicle(GeoPoint(37.5540, 126.9700))
        assertEquals(DemoRoutes.SEOUL.name, route.name)
        assertEquals(GeoPoint(37.5540, 126.9700), route.points.first())
    }

    @Test
    fun `어느 경로와도 먼 곳(부산)이면 경로를 그대로 쓴다`() {
        val route = DemoRoutes.forVehicle(GeoPoint(35.1587, 129.1604))
        assertEquals(DemoRoutes.ALL.first { it.name == route.name }.points, route.points)
    }

    @Test
    fun `판교 경로는 새로 추가한 명소 반경을 모두 지난다`() {
        val planner = NarrationPlanner(
            listOf(
                Poi("tv", "판교 테크노밸리", "", 37.4019, 127.1086, 600f, "", ""),
                Poi("hd", "현대백화점 판교점", "", 37.3925, 127.1121, 500f, "", ""),
                Poi("mr", "모란시장", "", 37.4318, 127.1290, 500f, "", ""),
                Poi("nh", "남한산성", "", 37.4784, 127.1820, 1200f, "", ""),
            ),
        )
        val narrated = mutableListOf<String>()
        val pts = DemoRoutes.SEONGNAM.points
        var t = 0L
        for (i in 0 until pts.lastIndex) {
            for (step in 0..50) {
                val p = GeoUtils.interpolate(pts[i], pts[i + 1], step / 50.0)
                planner.onLocation(p.latitude, p.longitude, t)?.let { narrated += it.id }
                t += 1_000
            }
        }
        assertEquals(listOf("tv", "hd", "mr", "nh"), narrated)
    }
}
