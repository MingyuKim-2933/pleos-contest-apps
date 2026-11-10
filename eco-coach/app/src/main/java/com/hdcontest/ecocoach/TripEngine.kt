package com.hdcontest.ecocoach

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hdcontest.ecocoach.core.CoachPolicy
import com.hdcontest.ecocoach.core.EcoMode
import com.hdcontest.ecocoach.core.EcoScore
import com.hdcontest.ecocoach.core.Powertrain
import com.hdcontest.ecocoach.core.TripAccumulator
import com.hdcontest.ecocoach.core.TripReport
import com.hdcontest.ecocoach.core.TripStats
import com.hdcontest.ecocoach.data.TripRepository
import com.hdcontest.ecocoach.vehicle.DemoSignalSource
import com.hdcontest.ecocoach.vehicle.PleosSignalSource
import com.hdcontest.ecocoach.vehicle.SignalSource
import com.hdcontest.ecocoach.voice.VoiceCoach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CoachUiState(
    val driving: Boolean = false,
    val demo: Boolean = false,
    val speedKmh: Double = 0.0,
    val speedLimitKmh: Int? = null,
    val liveScore: Int? = null,
    val liveStats: TripStats = TripStats(),
    val elapsedMs: Long = 0,
    val instantEfficiency: Float? = null,
    val powertrain: Powertrain = Powertrain.UNKNOWN,
    val driveMode: EcoMode = EcoMode.UNKNOWN,
    val energyPercent: Int? = null,
    val lastCoachMessage: String? = null,
    val status: String = "",
    val lastReport: TripReport? = null,
    val history: List<TripReport> = emptyList(),
)

/**
 * 주행 시작/종료를 판단하고, 주행 중 이벤트를 모아 음성 코칭과 주행 리포트를 만든다.
 *
 * - 시작: 차속 3km/h 초과
 * - 종료: P단 5초 유지 (기어 정보가 없으면 3분간 정차)
 * 모든 메서드는 메인 스레드에서 호출한다.
 */
class TripEngine(context: Context) : SignalSource.Listener {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val repository = TripRepository(appContext)
    private val voice = VoiceCoach(appContext)
    private val coach = CoachPolicy()

    private var source: SignalSource? = null
    private var accumulator: TripAccumulator? = null
    private var tripStartWallMs = 0L

    private var speedLimit: Int? = null
    private var powertrain = Powertrain.UNKNOWN
    private var driveMode = EcoMode.UNKNOWN
    private var parked: Boolean? = null
    private var parkedSinceMs: Long? = null
    private var lastMovingMs = 0L
    private var lastOdometerKm: Float? = null
    private var energyLatest: Int? = null
    private var energyAtStart: Int? = null

    private val _state = MutableStateFlow(CoachUiState())
    val state: StateFlow<CoachUiState> = _state.asStateFlow()

    private val ticker = object : Runnable {
        override fun run() {
            onTick(SystemClock.elapsedRealtime())
            handler.postDelayed(this, TICK_MS)
        }
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        voice.initialize()
        val history = repository.loadAll()
        _state.update {
            it.copy(history = history, lastReport = history.firstOrNull(), status = appContext.getString(R.string.status_waiting))
        }
        useSource(PleosSignalSource(appContext), demo = false)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun startDemo() {
        abortTrip()
        useSource(DemoSignalSource(), demo = true)
    }

    fun stopDemo() {
        abortTrip()
        useSource(PleosSignalSource(appContext), demo = false)
    }

    fun endTripManually() {
        if (accumulator != null) endTrip(SystemClock.elapsedRealtime())
    }

    fun release() {
        started = false
        handler.removeCallbacks(ticker)
        source?.stop()
        source = null
        voice.release()
    }

    private fun useSource(newSource: SignalSource, demo: Boolean) {
        source?.stop()
        resetSignals()
        source = newSource
        _state.update {
            it.copy(
                demo = demo,
                status = appContext.getString(if (demo) R.string.status_demo else R.string.status_waiting),
            )
        }
        newSource.start(this)
    }

    private fun resetSignals() {
        speedLimit = null
        parked = null
        parkedSinceMs = null
        lastOdometerKm = null
        energyLatest = null
        energyAtStart = null
    }

    // region SignalSource.Listener

    override fun onPowertrain(powertrain: Powertrain) {
        this.powertrain = powertrain
        _state.update { it.copy(powertrain = powertrain) }
    }

    override fun onSpeed(tMs: Long, speedKmh: Double) {
        if (speedKmh > 1.0) lastMovingMs = tMs
        if (accumulator == null && speedKmh > START_SPEED_KMH) beginTrip(tMs)

        accumulator?.let { acc ->
            acc.onSpeed(tMs, speedKmh, speedLimit).forEach { event ->
                coach.onEvent(event)?.let(::speak)
            }
        }
        _state.update { it.copy(speedKmh = speedKmh) }
    }

    override fun onGearParked(parked: Boolean) {
        // 같은 값이 반복해서 오면 P단 유지 시간을 다시 세지 않는다.
        if (this.parked == parked) return
        this.parked = parked
        parkedSinceMs = if (parked) SystemClock.elapsedRealtime() else null
    }

    override fun onBrakePedal(pressed: Boolean) {
        accumulator?.onBrakePedal(pressed)
    }

    override fun onDriveMode(mode: EcoMode) {
        driveMode = mode
        accumulator?.onDriveMode(SystemClock.elapsedRealtime(), mode)
        _state.update { it.copy(driveMode = mode) }
    }

    override fun onInstantEfficiency(value: Float) {
        _state.update { it.copy(instantEfficiency = value) }
    }

    override fun onEnergyLevel(percent: Int) {
        energyLatest = percent
        _state.update { it.copy(energyPercent = percent) }
    }

    override fun onOdometer(km: Float) {
        lastOdometerKm = km
        accumulator?.onOdometer(km)
    }

    override fun onSpeedLimit(kmh: Int?) {
        speedLimit = kmh
        _state.update { it.copy(speedLimitKmh = kmh) }
    }

    override fun onStatus(message: String) {
        _state.update { it.copy(status = message) }
    }

    // endregion

    private fun beginTrip(tMs: Long) {
        val acc = TripAccumulator(startMs = tMs)
        acc.onDriveMode(tMs, driveMode)
        lastOdometerKm?.let(acc::onOdometer)
        accumulator = acc
        tripStartWallMs = System.currentTimeMillis()
        energyAtStart = energyLatest
        lastMovingMs = tMs
        coach.reset(tMs)
        _state.update {
            it.copy(
                driving = true,
                liveScore = 100,
                liveStats = TripStats(),
                elapsedMs = 0,
                lastCoachMessage = null,
                status = appContext.getString(R.string.status_driving),
            )
        }
    }

    private fun onTick(nowMs: Long) {
        val acc = accumulator ?: return
        val elapsed = acc.elapsed(nowMs)
        val live = acc.currentStats.copy(durationMs = elapsed)
        _state.update {
            it.copy(liveStats = live, elapsedMs = elapsed, liveScore = EcoScore.compute(live, powertrain).total)
        }
        coach.onTick(nowMs)?.let(::speak)

        val parkedLongEnough = parked == true && parkedSinceMs?.let { nowMs - it >= PARK_END_MS } == true
        val stoppedLongEnough = parked == null && nowMs - lastMovingMs >= STOP_END_MS
        if (parkedLongEnough || stoppedLongEnough) endTrip(nowMs)
    }

    private fun endTrip(nowMs: Long) {
        val acc = accumulator ?: return
        accumulator = null
        val stats = acc.finish(nowMs)
        _state.update { it.copy(driving = false, status = appContext.getString(R.string.status_waiting)) }
        if (stats.distanceKm < MIN_TRIP_KM && stats.durationMs < MIN_TRIP_MS) return

        val startWall = tripStartWallMs
        val endWall = System.currentTimeMillis()
        val trainType = powertrain
        val used = energyAtStart?.let { start -> energyLatest?.let { start - it } }?.takeIf { it >= 0 }
        fun saveReport(efficiency: Float?) {
            val report = TripReport(
                id = startWall,
                startMs = startWall,
                endMs = endWall,
                powertrain = trainType,
                stats = stats,
                score = EcoScore.compute(stats, trainType),
                averageEfficiency = efficiency,
                energyUsedPercent = used,
            )
            val history = repository.add(report)
            _state.update { it.copy(lastReport = report, history = history) }
            speak(CoachPolicy.tripSummary(report))
        }

        val currentSource = source
        if (currentSource == null) saveReport(null) else currentSource.readTripEfficiency(::saveReport)
    }

    /** 데모 전환 등으로 진행 중인 주행을 저장하지 않고 버린다. */
    private fun abortTrip() {
        accumulator = null
        _state.update { it.copy(driving = false) }
    }

    private fun speak(text: String) {
        _state.update { it.copy(lastCoachMessage = text) }
        voice.say(text)
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val START_SPEED_KMH = 3.0
        const val PARK_END_MS = 5_000L
        const val STOP_END_MS = 3 * 60_000L
        const val MIN_TRIP_KM = 0.1
        const val MIN_TRIP_MS = 60_000L
    }
}
