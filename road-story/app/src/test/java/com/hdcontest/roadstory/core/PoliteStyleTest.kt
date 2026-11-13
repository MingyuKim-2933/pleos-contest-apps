package com.hdcontest.roadstory.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PoliteStyleTest {

    @Test
    fun `시연에 나온 TourAPI 개요를 해요체로 바꾼다`() {
        assertEquals(
            "단대동 닭죽촌은 성남시 수정구 단대동 일대 닭죽을 주로 판매하는 식당이 집중 조성된 곳이에요. " +
                "인근에 광주대단지가 조성됨에 따라 이주한 사람들이 양계장을 운영하며 닭죽 판매를 시작했어요.",
            PoliteStyle.convert(
                "단대동 닭죽촌은 성남시 수정구 단대동 일대 닭죽을 주로 판매하는 식당이 집중 조성된 곳이다. " +
                    "인근에 광주대단지가 조성됨에 따라 이주한 사람들이 양계장을 운영하며 닭죽 판매를 시작했다.",
            ),
        )
    }

    @Test
    fun `자주 쓰이는 문장 끝`() {
        mapOf(
            "전통 음식 거리이다." to "전통 음식 거리예요.",
            "산성이다." to "산성이에요.",
            "1960년대에 조성되었다." to "1960년대에 조성됐어요.",
            "정비하였다." to "정비했어요.",
            "볼거리가 많다." to "볼거리가 많아요.",
            "전망이 좋다." to "전망이 좋아요.",
            "주차장이 없다." to "주차장이 없어요.",
            "남한산성에 위치한다." to "남한산성에 위치해요.",
            "경관이 유명하다." to "경관이 유명해요.",
            "한국의 실리콘밸리로 불린다." to "한국의 실리콘밸리로 불려요.",
            "5일장이 열린다." to "5일장이 열려요.",
            "관광객이 많이 찾는다." to "관광객이 많이 찾아요.",
            "야경이 아름답다." to "야경이 아름다워요.",
            "길이 좁다." to "길이 좁아요.",
            "명소가 된다." to "명소가 돼요.",
            "도로가 있다." to "도로가 있어요.",
        ).forEach { (from, to) -> assertEquals(from, to, PoliteStyle.convert(from)) }
    }

    @Test
    fun `문장 중간과 이미 해요체인 문장은 그대로 둔다`() {
        val text = "성곽길을 걷다 보면 시내가 보여요. 바다."
        assertEquals(text, PoliteStyle.convert(text))
    }
}
