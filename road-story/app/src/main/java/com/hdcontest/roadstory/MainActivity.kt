package com.hdcontest.roadstory

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.hdcontest.roadstory.ui.NearbyAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val engine by lazy { (application as YeoginEodiApp).engine }
    private val nearbyAdapter = NearbyAdapter()

    private lateinit var phaseText: TextView
    private lateinit var categoryText: TextView
    private lateinit var poiNameText: TextView
    private lateinit var narrationText: TextView
    private lateinit var heardText: TextView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var demoSwitch: MaterialSwitch
    private lateinit var replayButton: MaterialButton
    private lateinit var visitButton: MaterialButton
    private lateinit var skipButton: MaterialButton

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val locationGranted = result[PERMISSION_FUSED_LOCATION] ?: hasLocationPermission()
            if (locationGranted || demoSwitch.isChecked) startGuide() else {
                statusText.setText(R.string.status_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        findViewById<RecyclerView>(R.id.nearbyList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = nearbyAdapter
        }

        toggleButton.setOnClickListener {
            if (engine.state.value.running) stopGuide() else requestPermissionsAndStart()
        }
        replayButton.setOnClickListener { engine.replay() }
        visitButton.setOnClickListener { engine.visitCurrent() }
        skipButton.setOnClickListener { engine.skip() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.state.collect(::render)
            }
        }
    }

    private fun bindViews() {
        phaseText = findViewById(R.id.phaseText)
        categoryText = findViewById(R.id.categoryText)
        poiNameText = findViewById(R.id.poiNameText)
        narrationText = findViewById(R.id.narrationText)
        heardText = findViewById(R.id.heardText)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        toggleButton = findViewById(R.id.toggleButton)
        demoSwitch = findViewById(R.id.demoSwitch)
        replayButton = findViewById(R.id.replayButton)
        visitButton = findViewById(R.id.visitButton)
        skipButton = findViewById(R.id.skipButton)
    }

    private fun render(state: GuideUiState) {
        toggleButton.setText(if (state.running) R.string.action_stop_guide else R.string.action_start_guide)
        demoSwitch.isEnabled = !state.running
        statusText.text = state.status

        phaseText.setText(
            when (state.phase) {
                GuidePhase.IDLE -> if (state.running) R.string.phase_watching else R.string.phase_off
                GuidePhase.NARRATING -> R.string.phase_narrating
                GuidePhase.LISTENING -> R.string.phase_listening
            }
        )

        val poi = state.currentPoi
        if (poi == null) {
            categoryText.visibility = View.GONE
            poiNameText.setText(R.string.empty_poi_title)
            narrationText.setText(R.string.empty_poi_body)
        } else {
            categoryText.visibility = View.VISIBLE
            categoryText.text = poi.category
            poiNameText.text = poi.name
            narrationText.text = state.narration
        }
        heardText.visibility = if (state.heardText.isNullOrBlank()) View.GONE else View.VISIBLE
        heardText.text = getString(R.string.heard_format, state.heardText ?: "")

        replayButton.isEnabled = poi != null
        visitButton.isEnabled = poi != null
        skipButton.isEnabled = state.phase != GuidePhase.IDLE

        val stats = getString(R.string.stats_format, state.narratedCount, state.visitCount)
        // 공공누리 조건에 따라 TourAPI 데이터를 쓸 때는 출처를 표시한다.
        statsText.text = if (state.tourPoiCount > 0) {
            stats + "  ·  " + getString(R.string.tour_attribution, state.tourPoiCount)
        } else {
            stats
        }
        nearbyAdapter.submit(state.nearby)
    }

    private fun requestPermissionsAndStart() {
        val needed = buildList {
            if (!demoSwitch.isChecked && !hasLocationPermission()) add(PERMISSION_FUSED_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startGuide() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, PERMISSION_FUSED_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startGuide() {
        engine.start(demoMode = demoSwitch.isChecked)
        if (engine.state.value.running) GuideService.start(this)
    }

    private fun stopGuide() {
        engine.stop()
        GuideService.stop(this)
    }

    private companion object {
        // FusedLocation SDK 의 위치 권한 (protection level: dangerous → 런타임 요청 필요)
        const val PERMISSION_FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    }
}
