package com.hdcontest.ecocoach.data

import android.content.Context
import android.util.Log
import com.hdcontest.ecocoach.core.Powertrain
import com.hdcontest.ecocoach.core.ScoreBreakdown
import com.hdcontest.ecocoach.core.TripReport
import com.hdcontest.ecocoach.core.TripStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 주행 리포트를 앱 내부 저장소에 JSON 으로 보관한다 (최근 [maxTrips]개). */
class TripRepository(context: Context, private val maxTrips: Int = 50) {

    private val file = File(context.filesDir, "trips.json")

    fun loadAll(): List<TripReport> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
    }.getOrElse {
        Log.w(TAG, "failed to read trips", it)
        emptyList()
    }

    /** 새 리포트를 맨 앞에 추가하고 전체 목록(최신순)을 반환한다. */
    fun add(report: TripReport): List<TripReport> {
        val trips = (listOf(report) + loadAll()).take(maxTrips)
        val array = JSONArray().apply { trips.forEach { put(toJson(it)) } }
        file.writeText(array.toString())
        return trips
    }

    private fun toJson(r: TripReport) = JSONObject().apply {
        put("id", r.id)
        put("startMs", r.startMs)
        put("endMs", r.endMs)
        put("powertrain", r.powertrain.name)
        r.averageEfficiency?.let { put("averageEfficiency", it.toDouble()) }
        r.energyUsedPercent?.let { put("energyUsedPercent", it) }
        put("stats", JSONObject().apply {
            put("durationMs", r.stats.durationMs)
            put("movingMs", r.stats.movingMs)
            put("stoppedMs", r.stats.stoppedMs)
            put("smoothMs", r.stats.smoothMs)
            put("speedingMs", r.stats.speedingMs)
            put("ecoModeMs", r.stats.ecoModeMs)
            put("sportModeMs", r.stats.sportModeMs)
            put("distanceKm", r.stats.distanceKm)
            put("maxSpeedKmh", r.stats.maxSpeedKmh)
            put("hardAccelCount", r.stats.hardAccelCount)
            put("hardBrakeCount", r.stats.hardBrakeCount)
            put("speedingCount", r.stats.speedingCount)
            put("brakePressCount", r.stats.brakePressCount)
        })
        put("score", JSONObject().apply {
            put("total", r.score.total)
            put("acceleration", r.score.acceleration)
            put("braking", r.score.braking)
            put("speedKeeping", r.score.speedKeeping)
            put("smoothness", r.score.smoothness)
            r.score.idling?.let { put("idling", it) }
            put("grade", r.score.grade)
            put("tips", JSONArray(r.score.tips))
        })
    }

    private fun fromJson(o: JSONObject): TripReport {
        val s = o.getJSONObject("stats")
        val sc = o.getJSONObject("score")
        val tips = sc.getJSONArray("tips")
        return TripReport(
            id = o.getLong("id"),
            startMs = o.getLong("startMs"),
            endMs = o.getLong("endMs"),
            powertrain = runCatching { Powertrain.valueOf(o.getString("powertrain")) }.getOrDefault(Powertrain.UNKNOWN),
            stats = TripStats(
                durationMs = s.getLong("durationMs"),
                movingMs = s.getLong("movingMs"),
                stoppedMs = s.getLong("stoppedMs"),
                smoothMs = s.getLong("smoothMs"),
                speedingMs = s.getLong("speedingMs"),
                ecoModeMs = s.getLong("ecoModeMs"),
                sportModeMs = s.getLong("sportModeMs"),
                distanceKm = s.getDouble("distanceKm"),
                maxSpeedKmh = s.getDouble("maxSpeedKmh"),
                hardAccelCount = s.getInt("hardAccelCount"),
                hardBrakeCount = s.getInt("hardBrakeCount"),
                speedingCount = s.getInt("speedingCount"),
                brakePressCount = s.getInt("brakePressCount"),
            ),
            score = ScoreBreakdown(
                total = sc.getInt("total"),
                acceleration = sc.getInt("acceleration"),
                braking = sc.getInt("braking"),
                speedKeeping = sc.getInt("speedKeeping"),
                smoothness = sc.getInt("smoothness"),
                idling = if (sc.has("idling")) sc.getInt("idling") else null,
                grade = sc.getString("grade"),
                tips = (0 until tips.length()).map { tips.getString(it) },
            ),
            averageEfficiency = if (o.has("averageEfficiency")) o.getDouble("averageEfficiency").toFloat() else null,
            energyUsedPercent = if (o.has("energyUsedPercent")) o.getInt("energyUsedPercent") else null,
        )
    }

    private companion object {
        const val TAG = "TripRepository"
    }
}
