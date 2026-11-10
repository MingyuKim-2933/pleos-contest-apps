package com.hdcontest.roadstory.core

data class DemoRoute(val name: String, val points: List<GeoPoint>)

/**
 * 시연용 가상 주행 경로.
 *
 * 내비게이션은 차량의 실제 위치에서 경로를 계산하므로, 데모도 차량 위치와 가까운 경로를 골라
 * 차량 위치에서 출발시킨다. 그래야 "들러줘"로 만든 내비 경로가 데모 화면과 맞는다.
 */
object DemoRoutes {

    val SEOUL = DemoRoute(
        name = "서울역 → 롯데월드타워",
        points = listOf(
            GeoPoint(37.5547, 126.9707), // 서울역
            GeoPoint(37.5600, 126.9753), // 숭례문
            GeoPoint(37.5700, 126.9768), // 광화문 사거리
            GeoPoint(37.5760, 126.9769), // 광화문
            GeoPoint(37.5775, 126.9830), // 안국역 방면
            GeoPoint(37.5770, 126.9905), // 창덕궁 앞
            GeoPoint(37.5710, 127.0020), // 종로5가
            GeoPoint(37.5665, 127.0080), // DDP
            GeoPoint(37.5450, 127.0350), // 성수 방면
            GeoPoint(37.5200, 127.0800), // 잠실 방면
            GeoPoint(37.5126, 127.1000), // 롯데월드타워
        ),
    )

    val SEONGNAM = DemoRoute(
        name = "판교 → 남한산성",
        points = listOf(
            GeoPoint(37.4127, 127.0947), // 성남 수정구 시흥동 (Pleos 에뮬레이터 기본 차량 위치)
            GeoPoint(37.4060, 127.1030),
            GeoPoint(37.4019, 127.1086), // 판교 테크노밸리
            GeoPoint(37.3925, 127.1115), // 현대백화점 판교점
            GeoPoint(37.4080, 127.1230),
            GeoPoint(37.4318, 127.1285), // 모란시장
            GeoPoint(37.4500, 127.1500),
            GeoPoint(37.4700, 127.1700),
            GeoPoint(37.4785, 127.1800), // 남한산성
        ),
    )

    val ALL = listOf(SEOUL, SEONGNAM)

    /** 차량 위치에서 이 거리 안에 경로가 있으면 차량 위치를 출발점으로 붙인다. */
    private const val ATTACH_START_METERS = 5_000.0

    /**
     * 차량 위치와 출발점이 가장 가까운 경로를 고른다. 위치를 모르면 서울 경로.
     * 출발점이 가까우면 차량 위치를 경로 맨 앞에 붙여 차량 위치에서 출발하게 한다.
     */
    fun forVehicle(vehicle: GeoPoint?): DemoRoute {
        vehicle ?: return SEOUL
        val (route, distance) = ALL
            .map { it to distance(vehicle, it.points.first()) }
            .minBy { it.second }
        return if (distance in 1.0..ATTACH_START_METERS) route.copy(points = listOf(vehicle) + route.points) else route
    }

    private fun distance(a: GeoPoint, b: GeoPoint) =
        GeoUtils.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}
