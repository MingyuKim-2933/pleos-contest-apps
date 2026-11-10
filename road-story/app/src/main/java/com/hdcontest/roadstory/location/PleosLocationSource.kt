package com.hdcontest.roadstory.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hdcontest.roadstory.core.Poi

/**
 * Pleos FusedLocation SDK 기반 위치 공급원.
 *
 * - registerFusedLocationCallback(): 주기적인 차량 위치 → 주변 장소 목록 / 앱 자체 지오펜스 판정
 * - addProximityAlert(): 장소별 근접 알림(ENTER/EXIT) → 서비스 측 지오펜스
 *
 * 두 경로 모두 NarrationPlanner 로 들어가며, 플래너가 중복 안내를 걸러낸다.
 * 런타임 권한(pleos.car.permission.FUSED_LOCATION)은 MainActivity 에서 요청한다.
 */
class PleosLocationSource(context: Context) : LocationSource {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fusedLocation: FusedLocation? = null
    private var listener: LocationSource.Listener? = null
    private val alertIds = mutableListOf<String>()

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            val mapped = VehicleLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                heading = location.heading.takeIf { it.isFinite() },
                timestampMillis = location.timestampMillis,
            )
            mainHandler.post { listener?.onLocation(mapped) }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "location error", exception)
            mainHandler.post { listener?.onError(exception.message ?: "위치 오류") }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(pois: Collection<Poi>, listener: LocationSource.Listener): Boolean {
        stop()
        val sdk = FusedLocation(appContext)
        if (!sdk.isSupported()) {
            Log.w(TAG, "FusedLocation SDK is not supported in this environment")
            return false
        }
        this.listener = listener
        sdk.initialize()
        fusedLocation = sdk

        try {
            sdk.getLastKnownLocation(locationListener)
            sdk.registerFusedLocationCallback(locationListener)
            pois.forEach { registerProximityAlert(sdk, it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "FUSED_LOCATION permission missing", e)
            listener.onError("위치 권한이 필요해요.")
            sdk.showLocationAccessBlockedPopup()
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun registerProximityAlert(sdk: FusedLocation, poi: Poi) {
        val request = ProximityAlertRequest(
            latitude = poi.latitude,
            longitude = poi.longitude,
            radiusMeters = poi.radiusMeters,
            transitionTypes = setOf(ProximityTransition.ENTER, ProximityTransition.EXIT),
        )
        sdk.addProximityAlert(request, object : ProximityAlertListener {
            override fun onRegistered(alertId: String) {
                mainHandler.post { alertIds += alertId }
            }

            override fun onTransition(event: ProximityAlertEvent) {
                mainHandler.post {
                    when (event.transition) {
                        ProximityTransition.ENTER -> listener?.onProximityEnter(poi.id)
                        ProximityTransition.EXIT -> listener?.onProximityExit(poi.id)
                    }
                }
            }

            override fun onError(exception: Exception) {
                // 근접 알림을 못 쓰더라도 위치 업데이트 기반 판정으로 동작은 유지된다.
                Log.w(TAG, "proximity alert error for ${poi.id}", exception)
            }
        })
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        val sdk = fusedLocation ?: return
        try {
            alertIds.forEach { sdk.removeProximityAlert(it) }
            sdk.unregisterFusedLocationCallback(locationListener)
        } catch (e: SecurityException) {
            Log.w(TAG, "stop failed", e)
        }
        alertIds.clear()
        sdk.release()
        fusedLocation = null
        listener = null
    }

    private companion object {
        const val TAG = "PleosLocationSource"
    }
}
