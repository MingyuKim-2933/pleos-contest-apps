package com.hdcontest.roadstory.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun `서울역에서 숭례문까지 거리는 약 700m`() {
        val d = GeoUtils.distanceMeters(37.5547, 126.9707, 37.5600, 126.9753)
        assertEquals(710.0, d, 30.0)
    }

    @Test
    fun `정북 방향 방위각은 0도`() {
        val bearing = GeoUtils.bearingDegrees(37.0, 127.0, 37.1, 127.0)
        assertEquals(0.0, bearing, 0.5)
    }

    @Test
    fun `정동 방향 방위각은 90도`() {
        val bearing = GeoUtils.bearingDegrees(37.0, 127.0, 37.0, 127.1)
        assertEquals(90.0, bearing, 0.5)
    }

    @Test
    fun `북쪽으로 달릴 때 동쪽 대상은 오른편`() {
        assertEquals(RelativeSide.RIGHT, GeoUtils.relativeSide(0f, 90.0))
        assertEquals(RelativeSide.LEFT, GeoUtils.relativeSide(0f, 270.0))
        assertEquals(RelativeSide.AHEAD, GeoUtils.relativeSide(0f, 10.0))
        assertEquals(RelativeSide.BEHIND, GeoUtils.relativeSide(0f, 180.0))
    }

    @Test
    fun `진행 방향이 350도일 때 10도 대상은 앞쪽`() {
        assertEquals(RelativeSide.AHEAD, GeoUtils.relativeSide(350f, 10.0))
    }

    @Test
    fun `heading 이 없으면 UNKNOWN`() {
        assertEquals(RelativeSide.UNKNOWN, GeoUtils.relativeSide(null, 90.0))
        assertEquals(RelativeSide.UNKNOWN, GeoUtils.relativeSide(Float.NaN, 90.0))
    }
}
