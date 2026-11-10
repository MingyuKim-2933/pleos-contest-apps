package com.hdcontest.roadstory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourApiTest {

    private val listJson = """
        {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
         "body":{"items":{"item":[
           {"addr1":"경기도 광주시 남한산성면 남한산성로 731","addr2":"","contentid":"126509","contenttypeid":"12",
            "dist":"812.3","mapx":"127.1820","mapy":"37.4784","title":"남한산성"},
           {"addr1":"경기도 성남시 분당구 판교역로 1","contentid":"999","contenttypeid":"39",
            "mapx":"127.1100","mapy":"37.3950","title":"판교 국수집"},
           {"contentid":"1000","contenttypeid":"39","mapx":"","mapy":"","title":"좌표 없음"}
         ]},"numOfRows":10,"pageNo":1,"totalCount":3}}}
    """.trimIndent()

    @Test
    fun `목록 응답을 파싱하고 좌표 없는 항목은 버린다`() {
        val result = TourApiParser.parseList(listJson) as TourResult.Success
        assertEquals(listOf("126509", "999"), result.value.map { it.contentId })
        assertEquals(37.4784, result.value[0].latitude, 1e-9)
        assertEquals(127.1820, result.value[0].longitude, 1e-9)
    }

    @Test
    fun `결과가 없으면 items 가 빈 문자열로 와도 빈 목록`() {
        val body = """{"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":"","totalCount":0}}}"""
        assertEquals(TourResult.Success(emptyList<TourItem>()), TourApiParser.parseList(body))
    }

    @Test
    fun `항목이 하나면 객체로 와도 처리한다`() {
        val body = """{"response":{"header":{"resultCode":"0000"},"body":{"items":{"item":
            {"contentid":"1","contenttypeid":"14","mapx":127.0,"mapy":37.5,"title":"박물관"}}}}}"""
        val result = TourApiParser.parseList(body) as TourResult.Success
        assertEquals("박물관", result.value.single().title)
    }

    @Test
    fun `인증키 오류 응답을 실패로 돌려준다`() {
        // 실제 잘못된 키로 호출했을 때 받은 응답 (HTTP 403)
        val body = """{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
            "returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}"""
        val result = TourApiParser.parseList(body) as TourResult.Failure
        assertEquals("30", result.code)
        assertTrue(TourErrorMessages.of(result).contains("인증키"))
    }

    @Test
    fun `JSON 이 아닌 응답도 실패로 처리한다`() {
        assertTrue(TourApiParser.parseList("<OpenAPI_ServiceResponse>…") is TourResult.Failure)
    }

    @Test
    fun `개요 파싱`() {
        val body = """{"response":{"header":{"resultCode":"0000"},"body":{"items":{"item":[
            {"contentid":"126509","overview":"남한산성은 <br>조선의 산성이다."}]}}}}"""
        assertEquals("남한산성은 <br>조선의 산성이다.", (TourApiParser.parseOverview(body) as TourResult.Success).value)
    }

    @Test
    fun `개요 요약은 태그와 한자 괄호를 지우고 문장 단위로 자른다`() {
        val raw = "경복궁(景福宮)은 1395년에 지어졌다.<br /><br />임진왜란 때 불탔다. &quot;광화문&quot;이 정문이다. " +
            "이후 여러 차례 복원 공사가 이어졌으며 지금도 진행 중이다. 이 문장은 길이 제한 때문에 빠져야 한다."
        val summary = OverviewSummarizer.summarize(raw, maxChars = 60)
        assertEquals("경복궁은 1395년에 지어졌다. 임진왜란 때 불탔다. \"광화문\"이 정문이다.", summary)
    }

    @Test
    fun `첫 문장이 너무 길면 단어 경계에서 자른다`() {
        val raw = "아주 ".repeat(100) + "긴 문장."
        val summary = OverviewSummarizer.summarize(raw, maxChars = 30)
        assertTrue(summary.endsWith("…"))
        assertTrue(summary.length <= 31)
    }

    @Test
    fun `TourAPI 항목을 Poi 로 바꾸고 개요가 없으면 기본 문장을 쓴다`() {
        val items = (TourApiParser.parseList(listJson) as TourResult.Success).value
        val restaurant = TourPoiMapper.toPoi(items[1], overview = null)!!
        assertEquals("tour-999", restaurant.id)
        assertEquals("맛집", restaurant.category)
        assertEquals(200f, restaurant.radiusMeters)
        assertEquals("한국관광공사에 등록된 맛집이에요.", restaurant.story)

        val street = TourPoiMapper.toPoi(items[1].copy(title = "신해철거리(성남)"), overview = null)!!
        assertEquals("신해철거리", street.name)

        val attraction = TourPoiMapper.toPoi(items[0], overview = "")!!
        assertEquals("한국관광공사에 등록된 관광지예요.", attraction.story)
    }

    @Test
    fun `내장 장소와 겹치는 TourAPI 장소는 빼고 나머지만 더한다`() {
        val local = listOf(Poi("namhansanseong", "남한산성", "문화유산", 37.4784, 127.1820, 1200f, "", "직접 쓴 이야기"))
        val remote = listOf(
            Poi("tour-1", "남한산성 (경기 광주)", "관광지", 37.4801, 127.1800, 700f, "", ""), // 이름 비슷, 250m
            Poi("tour-2", "남한산성 행궁", "관광지", 37.4790, 127.1825, 700f, "", ""),       // 80m 이내
            Poi("tour-3", "모란시장", "쇼핑", 37.4318, 127.1290, 300f, "", ""),
            Poi("tour-3", "모란시장", "쇼핑", 37.4318, 127.1290, 300f, "", ""),              // 중복 ID
        )
        val merged = PoiMerger.merge(local, remote)
        assertEquals(listOf("namhansanseong", "tour-3"), merged.map { it.id })
        assertEquals("직접 쓴 이야기", merged.first().story)
    }

    @Test
    fun `2km 이상 움직이거나 30분이 지나면 다시 조회한다`() {
        val policy = TourFetchPolicy()
        val pangyo = GeoPoint(37.4019, 127.1086)
        assertTrue(policy.shouldFetch(pangyo, 0))
        policy.onSuccess(pangyo, 0)
        assertFalse(policy.shouldFetch(GeoPoint(37.4100, 127.1086), 60_000))   // 약 900m
        assertTrue(policy.shouldFetch(GeoPoint(37.4250, 127.1086), 60_000))    // 약 2.6km
        assertTrue(policy.shouldFetch(pangyo, 31 * 60_000L))
    }

    @Test
    fun `실패하면 1분 동안 재시도하지 않는다`() {
        val policy = TourFetchPolicy()
        val p = GeoPoint(37.0, 127.0)
        policy.onFailure(0)
        assertFalse(policy.shouldFetch(p, 30_000))
        assertTrue(policy.shouldFetch(p, 61_000))
    }

    @Test
    fun `새로 받은 장소로 바꿔도 이미 안내한 장소의 쿨다운은 유지된다`() {
        val a = Poi("a", "가", "", 37.0, 127.0, 300f, "", "")
        val b = Poi("b", "나", "", 37.0, 127.0005, 300f, "", "")
        val planner = NarrationPlanner(listOf(a))
        assertEquals(a, planner.onLocation(37.0, 127.0, 0))
        planner.updatePois(listOf(a, b))
        // a 는 쿨다운 중, 새로 생긴 b 는 이미 반경 안이므로 바로 안내 대상
        assertEquals(b, planner.onLocation(37.0, 127.0, 1_000))
        assertEquals(null, planner.onLocation(37.0, 127.0, 2_000))
    }

    @Test
    fun `개요가 나중에 도착하면 같은 ID 로 최신 이야기를 찾을 수 있다`() {
        val before = Poi("tour-1", "라디오베이", "맛집", 37.0, 127.0, 200f, "", "한국관광공사에 등록된 맛집이에요.")
        val planner = NarrationPlanner(listOf(before))
        planner.updatePois(listOf(before.copy(story = "아메리칸 펍이에요.")))
        assertEquals("아메리칸 펍이에요.", planner.find("tour-1")?.story)
    }
}
