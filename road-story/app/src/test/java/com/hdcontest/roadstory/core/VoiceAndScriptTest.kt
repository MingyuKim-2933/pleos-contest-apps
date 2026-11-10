package com.hdcontest.roadstory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAndScriptTest {

    @Test
    fun `방문 의사 표현을 인식한다`() {
        listOf("들러줘", "응 가자", "네", "경유지로 추가해 줘", "좋아").forEach {
            assertEquals(it, VoiceIntent.VISIT, VoiceIntentParser.parse(it))
        }
    }

    @Test
    fun `부정 표현은 방문보다 우선한다`() {
        listOf("아니 안 들러도 돼", "괜찮아", "다음에 갈게").forEach {
            assertEquals(it, VoiceIntent.DECLINE, VoiceIntentParser.parse(it))
        }
    }

    @Test
    fun `다시 듣기와 빈 입력`() {
        assertEquals(VoiceIntent.REPEAT, VoiceIntentParser.parse("다시 말해줘"))
        assertEquals(VoiceIntent.UNKNOWN, VoiceIntentParser.parse(null))
        assertEquals(VoiceIntent.UNKNOWN, VoiceIntentParser.parse("오늘 날씨 어때"))
    }

    @Test
    fun `받침에 맞는 조사를 고른다`() {
        assertEquals("이", NarrationScript.subjectParticle("경복궁"))
        assertEquals("가", NarrationScript.subjectParticle("남산서울타워"))
        assertEquals("을", NarrationScript.objectParticle("숭례문"))
        assertEquals("를", NarrationScript.objectParticle("경포대"))
        assertEquals("으로", NarrationScript.roParticle("경복궁"))
        assertEquals("로", NarrationScript.roParticle("첨성대"))
        assertEquals("로", NarrationScript.roParticle("서울")) // ㄹ 받침
    }

    @Test
    fun `거리를 읽기 쉽게 표현한다`() {
        assertEquals("800미터", NarrationScript.formatDistance(790.0))
        assertEquals("1.5킬로미터", NarrationScript.formatDistance(1_480.0))
        assertEquals("2킬로미터", NarrationScript.formatDistance(2_010.0))
    }

    @Test
    fun `안내 문장에 방향과 장소가 들어간다`() {
        val poi = Poi("p", "경복궁", "궁궐", 0.0, 0.0, 1f, "", "조선의 법궁이에요.")
        val text = NarrationScript.full(poi, 800.0, RelativeSide.RIGHT)
        assertTrue(text, text.startsWith("800미터 오른편에 경복궁이 있어요."))
        assertTrue(text.contains("조선의 법궁이에요."))
    }
}
