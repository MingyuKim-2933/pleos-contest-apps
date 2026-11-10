package com.hdcontest.roadstory.core

/**
 * 장소 안내를 시작하는 거리.
 *
 * 안내 문장(약 20초) + 음성 응답(약 6초) + 경유지 반영과 차선 변경 여유(약 15초)를 고려해,
 * 현재 속도로 [leadSeconds] 동안 달리는 거리만큼 미리 안내한다.
 * 정차·저속일 때도 [minMeters] 보다 가까워지기 전에는 안내를 시작한다.
 */
data class TriggerDistance(
    val minMeters: Double = 800.0,
    val leadSeconds: Double = 45.0,
    val maxMeters: Double = 3_000.0,
) {
    fun forPoi(poi: Poi, speedMps: Double): Double =
        maxOf(poi.radiusMeters.toDouble(), minMeters, (speedMps * leadSeconds).coerceAtMost(maxMeters))

    companion object {
        /** 장소에 지정한 반경만 쓴다 (테스트용). */
        val RADIUS_ONLY = TriggerDistance(minMeters = 0.0, leadSeconds = 0.0)
    }
}

/**
 * 위치 업데이트/근접 알림을 받아 "지금 안내할 장소"를 결정한다.
 *
 * - 안내 거리([TriggerDistance]) 안에 처음 들어온 장소만 안내한다. 속도가 빠를수록 더 멀리서 안내한다.
 * - 이미 지나쳐 뒤쪽에 있는 장소는 안내하지 않는다.
 * - 여러 곳이 한꺼번에 들어오면 유명도([Poi.prominence])가 높은 곳, 그다음 가까운 곳을 고른다.
 * - 같은 장소는 [cooldownMs] 동안 다시 안내하지 않는다.
 *
 * 플랫폼 의존성이 없는 순수 로직이라 단위 테스트로 검증한다.
 */
class NarrationPlanner(
    pois: List<Poi>,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val trigger: TriggerDistance = TriggerDistance(),
) {
    private var poiById = pois.associateBy { it.id }
    private val lastNarratedAt = mutableMapOf<String, Long>()
    private val inside = mutableSetOf<String>()

    private var lastFix: Triple<Double, Double, Long>? = null

    /** 위치 변화로 추정한 현재 속도 (m/s) */
    var speedMps = 0.0
        private set

    val pois: Collection<Poi> get() = poiById.values

    /**
     * 안내 대상 목록을 바꾼다 (TourAPI 로 주변 장소를 새로 받았을 때).
     * 이미 안내한 장소의 쿨다운은 유지한다. 새로 생긴 장소의 안내 거리 안에 이미 있으면 다음 위치 업데이트에서 안내한다.
     */
    fun updatePois(pois: List<Poi>) {
        poiById = pois.associateBy { it.id }
        inside.retainAll(poiById.keys)
    }

    /**
     * 위치 업데이트마다 호출. 새로 들어온 장소 중 안내할 장소 하나를 반환한다.
     *
     * @param heading 진행 방향(도). 있으면 이미 지나친(뒤쪽) 장소를 거른다.
     */
    fun onLocation(latitude: Double, longitude: Double, nowMs: Long, heading: Float? = null): Poi? {
        updateSpeed(latitude, longitude, nowMs)
        val entered = mutableListOf<Pair<Poi, Double>>()
        for (poi in poiById.values) {
            val d = GeoUtils.distanceMeters(latitude, longitude, poi.latitude, poi.longitude)
            if (d <= trigger.forPoi(poi, speedMps)) {
                if (inside.add(poi.id) && !isBehind(latitude, longitude, heading, poi, d)) entered += poi to d
            } else {
                inside.remove(poi.id)
            }
        }
        return entered
            .sortedWith(compareByDescending<Pair<Poi, Double>> { it.first.prominence }.thenBy { it.second })
            .firstOrNull { canNarrate(it.first.id, nowMs) }
            ?.first
            ?.also { markNarrated(it.id, nowMs) }
    }

    private fun isBehind(lat: Double, lng: Double, heading: Float?, poi: Poi, distance: Double): Boolean {
        // 바로 옆(200m 이내)이면 방향과 상관없이 안내한다.
        if (heading == null || distance < 200) return false
        val bearing = GeoUtils.bearingDegrees(lat, lng, poi.latitude, poi.longitude)
        return GeoUtils.relativeSide(heading, bearing) == RelativeSide.BEHIND
    }

    private fun updateSpeed(lat: Double, lng: Double, nowMs: Long) {
        val prev = lastFix
        lastFix = Triple(lat, lng, nowMs)
        if (prev == null) return
        val dt = (nowMs - prev.third) / 1000.0
        if (dt <= 0.0) return
        if (dt > 30.0) {
            speedMps = 0.0
            return
        }
        val instant = GeoUtils.distanceMeters(prev.first, prev.second, lat, lng) / dt
        // 위치 튐을 줄이기 위해 지수 평균
        speedMps = if (speedMps == 0.0) instant else speedMps * 0.6 + instant * 0.4
    }

    /** 같은 ID 의 최신 장소 정보 (TourAPI 개요가 나중에 채워지는 경우를 위해). */
    fun find(poiId: String): Poi? = poiById[poiId]

    /** Pleos FusedLocation 근접 알림(ENTER) 수신 시 호출. */
    fun onProximityEnter(poiId: String, nowMs: Long): Poi? {
        val poi = poiById[poiId] ?: return null
        inside.add(poiId)
        if (!canNarrate(poiId, nowMs)) return null
        markNarrated(poiId, nowMs)
        return poi
    }

    fun onProximityExit(poiId: String) {
        inside.remove(poiId)
    }

    /** 현재 위치 기준 가까운 장소 목록. */
    fun nearby(latitude: Double, longitude: Double, heading: Float?, limit: Int = 5): List<NearbyPoi> =
        poiById.values
            .map { poi ->
                val d = GeoUtils.distanceMeters(latitude, longitude, poi.latitude, poi.longitude)
                val bearing = GeoUtils.bearingDegrees(latitude, longitude, poi.latitude, poi.longitude)
                NearbyPoi(poi, d, GeoUtils.relativeSide(heading, bearing))
            }
            .sortedBy { it.distanceMeters }
            .take(limit)

    private fun canNarrate(poiId: String, nowMs: Long): Boolean {
        val last = lastNarratedAt[poiId] ?: return true
        return nowMs - last >= cooldownMs
    }

    private fun markNarrated(poiId: String, nowMs: Long) {
        lastNarratedAt[poiId] = nowMs
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 30 * 60 * 1000L
    }
}
