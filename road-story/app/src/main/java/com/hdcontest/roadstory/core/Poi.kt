package com.hdcontest.roadstory.core

/**
 * 오디오 가이드 대상 장소.
 *
 * @property radiusMeters 이 반경 안에 들어오면 안내를 시작한다 (속도에 따라 더 멀리서 시작할 수 있다).
 * @property prominence 유명도. 여러 곳이 동시에 들어오면 높은 곳을 먼저 안내한다.
 *   직접 고른 대표 명소 = [CURATED], TourAPI 의 명소 분류 = [LISTED].
 */
data class Poi(
    val id: String,
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val address: String,
    val story: String,
    val prominence: Int = LISTED,
) {
    companion object {
        const val LISTED = 1
        const val CURATED = 2
    }
}

data class GeoPoint(val latitude: Double, val longitude: Double)

/** 현재 위치 기준으로 계산한 장소 정보. */
data class NearbyPoi(
    val poi: Poi,
    val distanceMeters: Double,
    val side: RelativeSide,
)

enum class RelativeSide { AHEAD, RIGHT, BEHIND, LEFT, UNKNOWN }
