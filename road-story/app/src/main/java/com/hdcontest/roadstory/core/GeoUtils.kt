package com.hdcontest.roadstory.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 두 좌표 사이의 거리(m). 하버사인 공식. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 시작점에서 도착점을 바라보는 방위각(0~360, 북=0, 시계 방향). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * 차량 진행 방향(heading) 기준으로 대상이 어느 쪽에 있는지 계산한다.
     * heading 이 유효하지 않으면 [RelativeSide.UNKNOWN].
     */
    fun relativeSide(headingDegrees: Float?, bearingToTarget: Double): RelativeSide {
        if (headingDegrees == null || headingDegrees.isNaN() || headingDegrees < 0f) return RelativeSide.UNKNOWN
        // -180 ~ 180 범위의 상대각
        val diff = ((bearingToTarget - headingDegrees + 540.0) % 360.0) - 180.0
        return when {
            diff in -35.0..35.0 -> RelativeSide.AHEAD
            diff in 35.0..145.0 -> RelativeSide.RIGHT
            diff in -145.0..-35.0 -> RelativeSide.LEFT
            else -> RelativeSide.BEHIND
        }
    }

    /** a→b 선분 위에서 fraction(0~1) 지점. 짧은 구간 보간용. */
    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint = GeoPoint(
        a.latitude + (b.latitude - a.latitude) * fraction,
        a.longitude + (b.longitude - a.longitude) * fraction,
    )
}
