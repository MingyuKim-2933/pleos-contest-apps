package com.hdcontest.roadstory.core

/** 안내 후 사용자의 짧은 음성 응답 해석 결과. */
enum class VoiceIntent { VISIT, DECLINE, REPEAT, UNKNOWN }

object VoiceIntentParser {
    // 부정 표현을 먼저 검사해야 "안 들러" 같은 문장을 VISIT 으로 오해하지 않는다.
    private val declineKeywords = listOf("아니", "안들", "안가", "괜찮", "됐어", "다음에", "싫어", "패스", "그만")
    private val repeatKeywords = listOf("다시", "한번더", "뭐라고", "못들")
    private val visitKeywords = listOf("들러", "들르", "가자", "가줘", "가볼", "경유", "응", "네", "예", "좋아", "그래", "오케이")

    fun parse(utterance: String?): VoiceIntent {
        if (utterance.isNullOrBlank()) return VoiceIntent.UNKNOWN
        val text = utterance.replace(" ", "").replace(".", "").replace("?", "").replace("!", "")
        return when {
            declineKeywords.any { text.contains(it) } -> VoiceIntent.DECLINE
            repeatKeywords.any { text.contains(it) } -> VoiceIntent.REPEAT
            visitKeywords.any { text.contains(it) } -> VoiceIntent.VISIT
            else -> VoiceIntent.UNKNOWN
        }
    }
}
