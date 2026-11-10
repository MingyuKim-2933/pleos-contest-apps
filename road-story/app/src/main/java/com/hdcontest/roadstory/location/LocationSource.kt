package com.hdcontest.roadstory.location

import com.hdcontest.roadstory.core.Poi

data class VehicleLocation(
    val latitude: Double,
    val longitude: Double,
    /** 진행 방향(도). 알 수 없으면 null. */
    val heading: Float?,
    val timestampMillis: Long,
)

/**
 * 위치 공급원. 실제 차량(Pleos FusedLocation)과 데모 시뮬레이션을 같은 방식으로 다룬다.
 */
interface LocationSource {
    interface Listener {
        fun onLocation(location: VehicleLocation)
        fun onProximityEnter(poiId: String) {}
        fun onProximityExit(poiId: String) {}
        fun onError(message: String) {}
    }

    /** 지원되지 않는 환경이면 false 를 반환한다. */
    fun start(pois: Collection<Poi>, listener: Listener): Boolean
    fun stop()
}
