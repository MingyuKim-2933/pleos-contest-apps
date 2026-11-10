package com.hdcontest.roadstory.core

/**
 * 오디오 가이드 대상 장소.
 *
 * @property radiusMeters 이 반경 안에 들어오면 안내를 시작한다.
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
)

data class GeoPoint(val latitude: Double, val longitude: Double)

/** 현재 위치 기준으로 계산한 장소 정보. */
data class NearbyPoi(
    val poi: Poi,
    val distanceMeters: Double,
    val side: RelativeSide,
)

enum class RelativeSide { AHEAD, RIGHT, BEHIND, LEFT, UNKNOWN }
