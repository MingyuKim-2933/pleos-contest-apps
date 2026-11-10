package com.hdcontest.ecocoach

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.hdcontest.ecocoach.core.EcoMode
import com.hdcontest.ecocoach.core.Powertrain
import com.hdcontest.ecocoach.core.TripReport
import com.hdcontest.ecocoach.ui.HistoryBarView
import com.hdcontest.ecocoach.ui.ScoreRingView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val engine by lazy { (application as EcoCoachApp).engine }
    private val dateFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

    private lateinit var scoreRing: ScoreRingView
    private lateinit var speedText: TextView
    private lateinit var limitText: TextView
    private lateinit var countersText: TextView
    private lateinit var efficiencyText: TextView
    private lateinit var coachMessageText: TextView
    private lateinit var statusText: TextView
    private lateinit var drivingPanel: View
    private lateinit var reportPanel: View
    private lateinit var reportTitle: TextView
    private lateinit var reportSummary: TextView
    private lateinit var subscoreContainer: LinearLayout
    private lateinit var tipsText: TextView
    private lateinit var historyChart: HistoryBarView
    private lateinit var demoButton: MaterialButton
    private lateinit var endTripButton: MaterialButton

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { engine.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        demoButton.setOnClickListener {
            if (engine.state.value.demo) engine.stopDemo() else engine.startDemo()
        }
        endTripButton.setOnClickListener { engine.endTripManually() }

        // 엔진은 Application 범위라 화면을 닫아도 주행 기록을 이어간다. start() 는 여러 번 불러도 안전하다.
        if (ContextCompat.checkSelfPermission(this, PERMISSION_CAR_SPEED) == PackageManager.PERMISSION_GRANTED) {
            engine.start()
        } else if (savedInstanceState == null) {
            permissionLauncher.launch(PERMISSION_CAR_SPEED)
        } else {
            engine.start()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.state.collect(::render)
            }
        }
    }

    private fun bindViews() {
        scoreRing = findViewById(R.id.scoreRing)
        speedText = findViewById(R.id.speedText)
        limitText = findViewById(R.id.limitText)
        countersText = findViewById(R.id.countersText)
        efficiencyText = findViewById(R.id.efficiencyText)
        coachMessageText = findViewById(R.id.coachMessageText)
        statusText = findViewById(R.id.statusText)
        drivingPanel = findViewById(R.id.drivingPanel)
        reportPanel = findViewById(R.id.reportPanel)
        reportTitle = findViewById(R.id.reportTitle)
        reportSummary = findViewById(R.id.reportSummary)
        subscoreContainer = findViewById(R.id.subscoreContainer)
        tipsText = findViewById(R.id.tipsText)
        historyChart = findViewById(R.id.historyChart)
        demoButton = findViewById(R.id.demoButton)
        endTripButton = findViewById(R.id.endTripButton)
    }

    private fun render(state: CoachUiState) {
        statusText.text = state.status
        demoButton.setText(if (state.demo) R.string.action_stop_demo else R.string.action_start_demo)

        // 왼쪽: 주행 중에는 실시간 점수, 정차 중에는 마지막 주행 점수
        if (state.driving) {
            scoreRing.score = state.liveScore
            scoreRing.caption = getString(R.string.caption_live)
        } else {
            scoreRing.score = state.lastReport?.score?.total
            scoreRing.caption = getString(if (state.lastReport != null) R.string.caption_last else R.string.caption_none)
        }

        speedText.text = getString(R.string.speed_format, state.speedKmh.roundToInt())
        limitText.visibility = if (state.speedLimitKmh != null) View.VISIBLE else View.GONE
        limitText.text = state.speedLimitKmh?.toString().orEmpty()

        val stats = state.liveStats
        countersText.text = getString(
            R.string.counters_format, stats.hardAccelCount, stats.hardBrakeCount, stats.speedingCount,
        )
        countersText.visibility = if (state.driving) View.VISIBLE else View.GONE
        efficiencyText.text = efficiencyLabel(state)
        coachMessageText.text = state.lastCoachMessage.orEmpty()
        coachMessageText.visibility = if (state.lastCoachMessage.isNullOrBlank()) View.GONE else View.VISIBLE

        // 오른쪽: 운전 중에는 상세 정보를 숨겨 주의 분산을 줄인다
        drivingPanel.visibility = if (state.driving) View.VISIBLE else View.GONE
        reportPanel.visibility = if (state.driving) View.GONE else View.VISIBLE
        if (!state.driving) renderReport(state.lastReport, state.history)
    }

    private fun efficiencyLabel(state: CoachUiState): String {
        val eff = state.instantEfficiency
        val energy = state.energyPercent?.let { getString(R.string.energy_format, it) }.orEmpty()
        val mode = when (state.driveMode) {
            EcoMode.ECO -> "ECO"
            EcoMode.NORMAL -> "NORMAL"
            EcoMode.SPORT -> "SPORT"
            EcoMode.UNKNOWN -> ""
        }
        val effText = when {
            eff == null -> ""
            state.powertrain == Powertrain.ICE -> getString(R.string.instant_fuel_format, eff)
            else -> getString(R.string.instant_ev_format, eff)
        }
        return listOf(mode, effText, energy).filter { it.isNotBlank() }.joinToString("  ·  ")
    }

    private fun renderReport(report: TripReport?, history: List<TripReport>) {
        historyChart.setScores(history.map { it.score.total })
        if (report == null) {
            reportTitle.setText(R.string.report_empty_title)
            reportSummary.setText(R.string.report_empty_body)
            subscoreContainer.removeAllViews()
            tipsText.visibility = View.GONE
            return
        }

        reportTitle.text = getString(R.string.report_title_format, report.score.grade, dateFormat.format(Date(report.startMs)))
        val minutes = (report.stats.durationMs / 60_000.0).roundToInt()
        val km = (report.stats.distanceKm * 10).roundToInt() / 10.0
        val efficiency = report.averageEfficiency?.let {
            if (report.powertrain == Powertrain.ICE) getString(R.string.avg_fuel_format, it)
            else getString(R.string.avg_ev_format, it)
        }
        reportSummary.text = listOfNotNull(
            getString(R.string.distance_format, km),
            getString(R.string.duration_format, minutes),
            efficiency,
            // 짧은 주행은 1%p 미만이라 0 으로 나오므로 숨긴다
            report.energyUsedPercent?.takeIf { it > 0 }?.let { getString(R.string.energy_used_format, it) },
        ).joinToString("  ·  ")

        subscoreContainer.removeAllViews()
        addSubscore(R.string.sub_accel, report.score.acceleration, getString(R.string.count_format, report.stats.hardAccelCount))
        addSubscore(R.string.sub_brake, report.score.braking, getString(R.string.count_format, report.stats.hardBrakeCount))
        addSubscore(R.string.sub_speed, report.score.speedKeeping, getString(R.string.count_format, report.stats.speedingCount))
        addSubscore(R.string.sub_smooth, report.score.smoothness, null)
        report.score.idling?.let { addSubscore(R.string.sub_idle, it, null) }

        tipsText.visibility = View.VISIBLE
        tipsText.text = report.score.tips.joinToString("\n") { "💡 $it" }
    }

    private fun addSubscore(labelRes: Int, score: Int, detail: String?) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_subscore, subscoreContainer, false)
        row.findViewById<TextView>(R.id.subscoreLabel).text =
            if (detail != null) "${getString(labelRes)} ($detail)" else getString(labelRes)
        row.findViewById<TextView>(R.id.subscoreValue).text = score.toString()
        row.findViewById<LinearProgressIndicator>(R.id.subscoreBar).apply {
            progress = score
            setIndicatorColor(ContextCompat.getColor(this@MainActivity, ScoreRingView.colorFor(score)))
        }
        subscoreContainer.addView(row)
    }

    private companion object {
        const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}
