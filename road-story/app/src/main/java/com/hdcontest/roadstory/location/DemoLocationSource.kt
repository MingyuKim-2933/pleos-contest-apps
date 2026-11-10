package com.hdcontest.roadstory.location

import android.os.Handler
import android.os.Looper
import com.hdcontest.roadstory.core.DemoRoute
import com.hdcontest.roadstory.core.GeoUtils
import com.hdcontest.roadstory.core.Poi

/**
 * 시연용 가상 주행. [demoRoute] 경로를 일정 간격으로 이동하며 위치를 내보낸다.
 * 에뮬레이터 위치가 고정돼 있어도 흐름을 확인할 수 있다.
 */
class DemoLocationSource(
    demoRoute: DemoRoute,
    // 약 100km/h. 안내 한 건(20~30초)이 다음 명소 진입 전에 끝나도록 맞춘 시연 속도
    private val metersPerTick: Double = 28.0,
    private val tickMs: Long = 1_000L,
) : LocationSource {

    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationSource.Listener? = null
    private var segment = 0
    private var progressMeters = 0.0

    private val route = demoRoute.points

    private val tick = object : Runnable {
        override fun run() {
            advance()
            handler.postDelayed(this, tickMs)
        }
    }

    override fun start(pois: Collection<Poi>, listener: LocationSource.Listener): Boolean {
        stop()
        this.listener = listener
        segment = 0
        progressMeters = 0.0
        handler.post(tick)
        return true
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        listener = null
    }

    private fun advance() {
        if (segment >= route.lastIndex) {
            // 경로 끝에 도달하면 처음부터 다시 돈다.
            segment = 0
            progressMeters = 0.0
        }
        val a = route[segment]
        val b = route[segment + 1]
        val length = GeoUtils.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        progressMeters += metersPerTick
        if (progressMeters >= length) {
            progressMeters -= length
            segment++
            if (segment >= route.lastIndex) return
        }
        val from = route[segment]
        val to = route[segment + 1]
        val segLength = GeoUtils.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        val point = GeoUtils.interpolate(from, to, (progressMeters / segLength).coerceIn(0.0, 1.0))
        val heading = GeoUtils.bearingDegrees(from.latitude, from.longitude, to.latitude, to.longitude)
        listener?.onLocation(
            VehicleLocation(point.latitude, point.longitude, heading.toFloat(), System.currentTimeMillis())
        )
    }
}
