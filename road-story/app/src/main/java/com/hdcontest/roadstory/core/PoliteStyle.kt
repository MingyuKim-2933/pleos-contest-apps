package com.hdcontest.roadstory.core

/**
 * TourAPI 개요의 문어체 문장 끝("~곳이다.", "~시작했다.")을 안내 음성에 맞는 해요체로 바꾼다.
 *
 * 문장 끝 어절만 바꾸며, 규칙으로 처리하기 어려운 어미는 그대로 둔다.
 * - 이다 → 이에요/예요, ~했다 → ~했어요, 있다 → 있어요
 * - 한다/하다 → 해요, 된다 → 돼요, 불린다 → 불려요, 찾는다 → 찾아요
 * - 많다 → 많아요, 아름답다 → 아름다워요
 */
object PoliteStyle {

    // 문장 끝 어절: 한글 어절 + 다 + 마침표·느낌표(또는 글 끝). "걷다 보면" 같은 문장 중간은 건드리지 않는다.
    private val sentenceEnd = Regex("([가-힣]+)다(?=[.!]|$)")

    // 받침 ㅂ 이 있어도 불규칙 활용하지 않는 형용사·동사 어간
    private val regularB = setOf('좁', '잡', '입', '씹', '뽑', '접', '집', '굽')

    fun convert(text: String): String = sentenceEnd.replace(text) { m ->
        val word = m.groupValues[1]
        politeOf(word) ?: m.value
    }

    /** "곳이" + 다 → "곳이에요" 처럼, '다' 앞부분을 받아 해요체 어절을 돌려준다. 모르면 null. */
    private fun politeOf(stem: String): String? {
        val last = stem.last()
        val head = stem.dropLast(1)
        if (!isHangul(last)) return null
        val (cho, jung, jong) = decompose(last)

        // 1) 서술격 조사 '이다': "곳이다" → "곳이에요", "거리이다" → "거리예요"
        if (last == '이' && head.isNotEmpty()) {
            val prev = head.last()
            return if (isHangul(prev) && decompose(prev).third == 0) "${head}예요" else "${head}이에요"
        }
        // 2) 하였/되었 → 했/됐 으로 줄인 뒤 과거형 처리
        if (stem.endsWith("하였")) return politeOf(stem.dropLast(2) + "했")
        if (stem.endsWith("되었")) return politeOf(stem.dropLast(2) + "됐")
        // 3) 받침 ㅆ(과거·있다): "했다" → "했어요", "있다" → "있어요"
        if (jong == JONG_SS) return "${stem}어요"
        // 4) 하다/한다 → 해요, 되다/된다 → 돼요
        if (stem.endsWith("한")) return "${stem.dropLast(1)}해요"
        if (last == '하') return "${head}해요"
        if (stem.endsWith("된")) return "${stem.dropLast(1)}돼요"
        if (last == '되') return "${head}돼요"
        // 5) 받침 있는 어간 + 는다: "찾는다" → "찾아요"
        if (last == '는' && head.isNotEmpty() && isHangul(head.last()) && decompose(head.last()).third != 0) {
            return head.dropLast(1) + withEnding(head.last())
        }
        // 6) 받침 ㄴ 으로 끝나는 현재형: "불린다" → "불려요", "간다" → "가요"
        if (jong == JONG_N && head.isNotEmpty()) {
            return head + contract(compose(cho, jung, 0))
        }
        // 7) 받침 있는 형용사·동사 어간: "많다" → "많아요", "아름답다" → "아름다워요"
        if (jong != 0) {
            if (jong == JONG_B && last !in regularB) return head + compose(cho, jung, 0) + "워요"
            return head + withEnding(last)
        }
        return null
    }

    /** 받침 있는 어간 + 아요/어요 */
    private fun withEnding(syllable: Char): String {
        val jung = decompose(syllable).second
        return "$syllable" + if (jung == JUNG_A || jung == JUNG_O) "아요" else "어요"
    }

    /** 받침 없는 어간 + 아요/어요 를 줄여 쓴다: 가 → 가요, 리 → 려요, 보 → 봐요 */
    private fun contract(syllable: Char): String {
        val (cho, jung, _) = decompose(syllable)
        val merged = when (jung) {
            JUNG_A, JUNG_EO, JUNG_AE, JUNG_E, JUNG_YEO -> jung
            JUNG_I -> JUNG_YEO
            JUNG_O -> JUNG_WA
            JUNG_U -> JUNG_WO
            JUNG_EU -> JUNG_EO
            else -> return "${syllable}어요"
        }
        return "${compose(cho, merged, 0)}요"
    }

    private fun isHangul(c: Char) = c in '가'..'힣'

    private fun decompose(c: Char): Triple<Int, Int, Int> {
        val code = c - '가'
        return Triple(code / (21 * 28), (code % (21 * 28)) / 28, code % 28)
    }

    private fun compose(cho: Int, jung: Int, jong: Int): Char = '가' + (cho * 21 * 28 + jung * 28 + jong)

    private const val JUNG_A = 0
    private const val JUNG_AE = 1
    private const val JUNG_EO = 4
    private const val JUNG_E = 5
    private const val JUNG_YEO = 6
    private const val JUNG_O = 8
    private const val JUNG_WA = 9
    private const val JUNG_U = 13
    private const val JUNG_WO = 14
    private const val JUNG_EU = 18
    private const val JUNG_I = 20
    private const val JONG_N = 4
    private const val JONG_B = 17
    private const val JONG_SS = 20
}
