package com.hdcontest.roadstory.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hdcontest.roadstory.core.GeoPoint
import com.hdcontest.roadstory.core.Poi

/**
 * Pleos NaviHelper SDK 래퍼.
 *
 * 안내 중인 경로가 있으면 장소를 경유지로 추가하고, 없으면 해당 장소를 목적지로 설정한다.
 * 경유지는 최대 2개(FIRST, SECOND)까지 지원된다.
 */
class NaviBridge(context: Context) {

    enum class VisitResult { WAYPOINT_ADDED, DESTINATION_SET, UNAVAILABLE }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var naviHelper: NaviHelper? = null
    private var connected = false

    private var hasActiveRoute = false
    private var waypoints: List<WaypointInfo> = emptyList()

    var onError: ((String) -> Unit)? = null

    /** 내비게이션이 알고 있는 차량 위치. 경로는 이 위치에서 계산된다. */
    var vehicleLocation: GeoPoint? = null
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            mainHandler.post { hasActiveRoute = status == NavigationStatus.ROUTE_DRIVE }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            mainHandler.post {
                vehicleLocation = GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)
            }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            mainHandler.post { hasActiveRoute = routeStateInfo.isRouting }
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            mainHandler.post { hasActiveRoute = true }
        }

        override fun onRouteCancelled() {
            mainHandler.post { clearRoute() }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            mainHandler.post { clearRoute() }
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            mainHandler.post { waypoints = waypointInfoList }
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            refreshWaypoints()
        }

        override fun onError(errorCode: NaviErrorCode) {
            val message = when (errorCode) {
                NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "목적지가 너무 가까워요."
                NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "탐색 가능한 거리를 넘었어요."
                NaviErrorCode.CAUSE_LOW_BATTERY -> "배터리가 부족해 경로를 찾을 수 없어요."
                else -> "경로 탐색에 실패했어요."
            }
            mainHandler.post { onError?.invoke(message) }
        }
    }

    fun start() {
        if (naviHelper != null) return
        val helper = NaviHelper(appContext)
        naviHelper = helper
        helper.initialize(
            onConnected = {
                mainHandler.post {
                    connected = true
                    helper.addListener(listener)
                    refreshState()
                }
            },
            onFailed = { throwable ->
                Log.w(TAG, "NaviHelper connection failed", throwable)
                mainHandler.post { connected = false }
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun refreshState() {
        val helper = naviHelper ?: return
        helper.getRouteStateInfo()
        helper.getWaypointInfo()
        helper.getCurrentLocationInfo()
    }

    @SuppressLint("MissingPermission")
    private fun refreshWaypoints() {
        naviHelper?.takeIf { connected }?.getWaypointInfo()
    }

    @SuppressLint("MissingPermission")
    fun visit(poi: Poi): VisitResult {
        val helper = naviHelper?.takeIf { connected } ?: return VisitResult.UNAVAILABLE
        return if (hasActiveRoute) {
            val index = if (waypoints.isEmpty()) WaypointIndex.FIRST else WaypointIndex.SECOND
            helper.addWaypoint(
                RequestWaypointInfo(
                    latitude = poi.latitude,
                    longitude = poi.longitude,
                    poiName = poi.name,
                    waypointIndex = index,
                    // 내비 POI 검색 없이 좌표로 요청하므로 POI ID 는 비워 둔다.
                    poiId = "",
                    address = poi.address,
                    poiSubId = "0",
                )
            )
            refreshWaypoints()
            VisitResult.WAYPOINT_ADDED
        } else {
            helper.requestRoute(
                RouteInfo(
                    latitude = poi.latitude,
                    longitude = poi.longitude,
                    poiName = poi.name,
                    poiId = "",
                    address = poi.address,
                    poiSubId = "0",
                )
            )
            VisitResult.DESTINATION_SET
        }
    }

    private fun clearRoute() {
        hasActiveRoute = false
        waypoints = emptyList()
    }

    fun stop() {
        naviHelper?.run {
            removeListener(listener)
            release()
        }
        naviHelper = null
        connected = false
        clearRoute()
    }

    private companion object {
        const val TAG = "NaviBridge"
    }
}
