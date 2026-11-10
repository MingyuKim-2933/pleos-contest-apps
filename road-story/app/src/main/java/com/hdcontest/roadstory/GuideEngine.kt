package com.hdcontest.roadstory

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hdcontest.roadstory.core.DemoRoutes
import com.hdcontest.roadstory.core.GeoUtils
import com.hdcontest.roadstory.core.NarrationPlanner
import com.hdcontest.roadstory.core.NarrationScript
import com.hdcontest.roadstory.core.NearbyPoi
import com.hdcontest.roadstory.core.Poi
import com.hdcontest.roadstory.core.PoiMerger
import com.hdcontest.roadstory.core.RelativeSide
import com.hdcontest.roadstory.core.VoiceIntent
import com.hdcontest.roadstory.core.VoiceIntentParser
import com.hdcontest.roadstory.data.TourPoiProvider
import com.hdcontest.roadstory.location.DemoLocationSource
import com.hdcontest.roadstory.location.LocationSource
import com.hdcontest.roadstory.location.PleosLocationSource
import com.hdcontest.roadstory.location.VehicleLocation
import com.hdcontest.roadstory.navi.NaviBridge
import com.hdcontest.roadstory.voice.VoiceGuide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class GuidePhase { IDLE, NARRATING, LISTENING }

data class GuideUiState(
    val running: Boolean = false,
    val demoMode: Boolean = false,
    val phase: GuidePhase = GuidePhase.IDLE,
    val currentPoi: Poi? = null,
    val narration: String = "",
    val heardText: String? = null,
    val status: String = "",
    val nearby: List<NearbyPoi> = emptyList(),
    val narratedCount: Int = 0,
    val visitCount: Int = 0,
    /** TourAPI 에서 받아 안내 대상에 더한 장소 수 (출처 표시용) */
    val tourPoiCount: Int = 0,
)

/**
 * 위치 → 안내 결정 → 음성 안내 → 음성 응답 → 내비 연동 흐름을 관리한다.
 * 모든 메서드는 메인 스레드에서 호출한다.
 */
class GuideEngine(context: Context) : LocationSource.Listener {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val localPois = PoiRepository.load(appContext)
    private val planner = NarrationPlanner(localPois)
    private val tour = TourPoiProvider(
        appContext,
        BuildConfig.TOUR_API_KEY,
        onPois = ::applyTourPois,
        onError = { message -> _state.update { it.copy(status = message) } },
    )
    private val voice = VoiceGuide(appContext)
    private val navi = NaviBridge(appContext)
    private var source: LocationSource? = null

    private var lastLocation: VehicleLocation? = null
    private var pendingPoi: Poi? = null
    private var repeatUsed = false

    /** "들러줘" 직후에는 내비가 경로 안내 음성을 내므로, 이 시각까지 새 안내를 미룬다. */
    private var quietUntilMs = 0L
    private val resumeAfterQuiet = Runnable {
        // 그사이 사용자가 다시 듣기 등으로 안내를 시작했다면 그 흐름이 끝날 때 이어간다.
        if (_state.value.phase == GuidePhase.IDLE) finishInteraction()
    }

    private val _state = MutableStateFlow(GuideUiState(status = appContext.getString(R.string.status_ready)))
    val state: StateFlow<GuideUiState> = _state.asStateFlow()

    /** TTS 콜백이 오지 않는 경우를 대비해 안내 단계를 강제로 끝낸다. */
    private val phaseWatchdog = Runnable { finishInteraction() }

    init {
        navi.onError = { message -> _state.update { it.copy(status = message) } }
        // 데모 경로를 고를 때 차량 위치가 필요하므로 내비는 미리 연결해 둔다.
        navi.start()
        tour.restore()
    }

    fun start(demoMode: Boolean) {
        stop()
        voice.initialize()
        navi.start()
        val demoRoute = DemoRoutes.forVehicle(navi.vehicleLocation)
        val newSource = if (demoMode) DemoLocationSource(demoRoute) else PleosLocationSource(appContext)
        val started = newSource.start(planner.pois, this)
        if (!started) {
            _state.update {
                it.copy(running = false, status = appContext.getString(R.string.status_location_unsupported))
            }
            return
        }
        source = newSource
        _state.update {
            it.copy(
                running = true,
                demoMode = demoMode,
                status = if (demoMode) {
                    appContext.getString(R.string.status_demo_running, demoRoute.name)
                } else {
                    appContext.getString(R.string.status_running)
                },
            )
        }
    }

    fun stop() {
        source?.stop()
        source = null
        voice.stopAll()
        handler.removeCallbacks(phaseWatchdog)
        handler.removeCallbacks(resumeAfterQuiet)
        pendingPoi = null
        quietUntilMs = 0L
        _state.update {
            it.copy(running = false, phase = GuidePhase.IDLE, status = appContext.getString(R.string.status_ready))
        }
    }

    fun release() {
        stop()
        voice.release()
        navi.stop()
        tour.release()
    }

    // region LocationSource.Listener

    override fun onLocation(location: VehicleLocation) {
        lastLocation = location
        tour.onLocation(location.latitude, location.longitude)
        val nearby = planner.nearby(location.latitude, location.longitude, location.heading)
        _state.update { it.copy(nearby = nearby) }
        planner.onLocation(location.latitude, location.longitude, System.currentTimeMillis(), location.heading)
            ?.let(::enqueue)
    }

    override fun onProximityEnter(poiId: String) {
        planner.onProximityEnter(poiId, System.currentTimeMillis())?.let(::enqueue)
    }

    override fun onProximityExit(poiId: String) {
        planner.onProximityExit(poiId)
    }

    override fun onError(message: String) {
        _state.update { it.copy(status = message) }
    }

    // endregion

    // region 사용자 조작 (화면 버튼)

    fun replay() {
        val poi = _state.value.currentPoi ?: return
        narrate(poi)
    }

    fun visitCurrent() {
        val poi = _state.value.currentPoi ?: return
        voice.stopAll()
        visit(poi)
    }

    fun skip() {
        voice.stopAll()
        finishInteraction()
    }

    // endregion

    private fun applyTourPois(tourPois: List<Poi>) {
        val merged = PoiMerger.merge(localPois, tourPois)
        planner.updatePois(merged)
        val added = merged.size - localPois.size
        val nearby = lastLocation?.let { planner.nearby(it.latitude, it.longitude, it.heading) }
        _state.update { it.copy(tourPoiCount = added, nearby = nearby ?: it.nearby) }
    }

    private fun enqueue(poi: Poi) {
        if (_state.value.phase == GuidePhase.IDLE && System.currentTimeMillis() >= quietUntilMs) {
            repeatUsed = false
            narrate(poi)
        } else {
            // 안내 중에 다른 장소에 진입하면 가장 최근 장소 하나만 대기시킨다.
            pendingPoi = poi
            val quietLeft = quietUntilMs - System.currentTimeMillis()
            if (_state.value.phase == GuidePhase.IDLE && quietLeft > 0) {
                handler.removeCallbacks(resumeAfterQuiet)
                handler.postDelayed(resumeAfterQuiet, quietLeft)
            }
        }
    }

    private fun narrate(target: Poi) {
        // 대기 중에 TourAPI 개요가 도착했을 수 있으므로 최신 정보로 안내한다.
        val poi = planner.find(target.id) ?: target
        val location = lastLocation
        val distance = location?.let {
            GeoUtils.distanceMeters(it.latitude, it.longitude, poi.latitude, poi.longitude)
        }
        val side = location?.let {
            GeoUtils.relativeSide(it.heading, GeoUtils.bearingDegrees(it.latitude, it.longitude, poi.latitude, poi.longitude))
        } ?: RelativeSide.UNKNOWN
        val text = NarrationScript.full(poi, distance, side)

        _state.update {
            it.copy(
                phase = GuidePhase.NARRATING,
                currentPoi = poi,
                narration = text,
                heardText = null,
                narratedCount = if (it.currentPoi?.id == poi.id) it.narratedCount else it.narratedCount + 1,
            )
        }
        armWatchdog()
        voice.speak(text) { listenForAnswer(poi) }
    }

    private fun listenForAnswer(poi: Poi) {
        _state.update { it.copy(phase = GuidePhase.LISTENING) }
        armWatchdog()
        voice.listen { heard ->
            _state.update { it.copy(heardText = heard) }
            when (VoiceIntentParser.parse(heard)) {
                VoiceIntent.VISIT -> visit(poi)
                VoiceIntent.REPEAT -> if (!repeatUsed) {
                    repeatUsed = true
                    narrate(poi)
                } else {
                    finishInteraction()
                }
                VoiceIntent.DECLINE, VoiceIntent.UNKNOWN -> finishInteraction()
            }
        }
    }

    private fun visit(poi: Poi) {
        val result = navi.visit(poi)
        val message = when (result) {
            NaviBridge.VisitResult.WAYPOINT_ADDED -> NarrationScript.waypointAdded(poi)
            NaviBridge.VisitResult.DESTINATION_SET -> NarrationScript.destinationSet(poi)
            NaviBridge.VisitResult.UNAVAILABLE -> NarrationScript.naviUnavailable()
        }
        val visited = result != NaviBridge.VisitResult.UNAVAILABLE
        _state.update {
            it.copy(
                phase = GuidePhase.NARRATING,
                status = message,
                visitCount = if (visited) it.visitCount + 1 else it.visitCount,
            )
        }
        armWatchdog()
        voice.speak(message) {
            // 내비가 경로 안내를 시작하며 말하는 동안 끼어들지 않는다.
            if (visited) quietUntilMs = System.currentTimeMillis() + NAVI_QUIET_MS
            finishInteraction()
        }
    }

    private fun finishInteraction() {
        handler.removeCallbacks(phaseWatchdog)
        _state.update { it.copy(phase = GuidePhase.IDLE) }
        val quietLeft = quietUntilMs - System.currentTimeMillis()
        if (quietLeft > 0) {
            // 대기 중인 장소는 조용한 시간이 끝난 뒤에 안내한다.
            if (pendingPoi != null) {
                handler.removeCallbacks(resumeAfterQuiet)
                handler.postDelayed(resumeAfterQuiet, quietLeft)
            }
            return
        }
        val next = pendingPoi ?: return
        pendingPoi = null
        // 기다리는 사이 이미 지나쳤거나 멀어진 곳은 안내하지 않는다.
        val here = lastLocation
        if (here != null && !planner.isStillAhead(next, here.latitude, here.longitude, here.heading)) return
        repeatUsed = false
        narrate(next)
    }

    private fun armWatchdog() {
        handler.removeCallbacks(phaseWatchdog)
        handler.postDelayed(phaseWatchdog, WATCHDOG_MS)
    }

    private companion object {
        const val WATCHDOG_MS = 90_000L
        const val NAVI_QUIET_MS = 30_000L
    }
}
