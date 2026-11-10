package com.hdcontest.roadstory.core

import kotlin.math.roundToInt

/** TTS 로 읽어줄 안내 문장을 만든다. 운전 중 듣기 좋게 짧은 문장 단위로 구성한다. */
object NarrationScript {

    fun intro(poi: Poi, distanceMeters: Double?, side: RelativeSide): String {
        val where = buildString {
            if (distanceMeters != null && distanceMeters >= 100) append("${formatDistance(distanceMeters)} ")
            when (side) {
                RelativeSide.AHEAD -> append("앞쪽에")
                RelativeSide.RIGHT -> append("오른편에")
                RelativeSide.LEFT -> append("왼편에")
                RelativeSide.BEHIND, RelativeSide.UNKNOWN -> append("근처에")
            }
        }
        return "$where ${poi.name}${subjectParticle(poi.name)} 있어요."
    }

    fun full(poi: Poi, distanceMeters: Double?, side: RelativeSide): String =
        "${intro(poi, distanceMeters, side)} ${poi.story} 들러보시려면 '들러줘'라고 말씀해 주세요."

    fun waypointAdded(poi: Poi) = "${poi.name}${objectParticle(poi.name)} 경유지로 추가했어요."

    fun destinationSet(poi: Poi) = "${poi.name}${roParticle(poi.name)} 안내를 시작할게요."

    fun naviUnavailable() = "지금은 내비게이션에 연결할 수 없어요."

    fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> {
            val km = (meters / 100).roundToInt() / 10.0
            if (km % 1.0 == 0.0) "${km.toInt()}킬로미터" else "${km}킬로미터"
        }
        else -> "${((meters / 50).roundToInt() * 50).coerceAtLeast(50)}미터"
    }

    // 받침 유무에 따른 조사 선택 (한글 음절이 아닌 경우 받침 없음으로 처리)
    private fun hasFinalConsonant(word: String): Boolean {
        val last = word.trim().lastOrNull() ?: return false
        if (last !in '가'..'힣') return false
        return (last - '가') % 28 != 0
    }

    private fun hasRieulFinal(word: String): Boolean {
        val last = word.trim().lastOrNull() ?: return false
        if (last !in '가'..'힣') return false
        return (last - '가') % 28 == 8
    }

    /** "관광지예요" / "맛집이에요" */
    fun copula(word: String) = if (hasFinalConsonant(word)) "이에요" else "예요"

    fun subjectParticle(word: String) = if (hasFinalConsonant(word)) "이" else "가"

    fun objectParticle(word: String) = if (hasFinalConsonant(word)) "을" else "를"

    fun roParticle(word: String) = if (hasFinalConsonant(word) && !hasRieulFinal(word)) "으로" else "로"
}
