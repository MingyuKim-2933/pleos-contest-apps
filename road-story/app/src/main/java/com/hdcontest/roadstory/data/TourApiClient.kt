package com.hdcontest.roadstory.data

import com.hdcontest.roadstory.core.TourApiParser
import com.hdcontest.roadstory.core.TourContentType
import com.hdcontest.roadstory.core.TourItem
import com.hdcontest.roadstory.core.TourResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 한국관광공사 TourAPI 4.0 (KorService2) 호출. 블로킹 함수이므로 IO 디스패처에서 부른다.
 *
 * @param serviceKey 공공데이터포털에서 발급받은 인증키. Encoding/Decoding 키 어느 쪽을 넣어도 된다.
 */
class TourApiClient(serviceKey: String) {

    // Encoding 키는 이미 % 인코딩되어 있으므로 그대로 쓰고, Decoding 키만 인코딩한다.
    private val encodedKey = if (serviceKey.contains('%')) serviceKey else URLEncoder.encode(serviceKey, "UTF-8")

    fun locationBasedList(latitude: Double, longitude: Double, type: TourContentType): TourResult<List<TourItem>> =
        get(
            "locationBasedList2",
            mapOf(
                "mapX" to longitude.toString(),
                "mapY" to latitude.toString(),
                "radius" to type.searchRadiusMeters.toString(),
                "contentTypeId" to type.code,
                // 거리순 + 대표 이미지가 있는 곳만 (사진까지 등록된 곳이 대체로 잘 알려진 곳이다)
                "arrange" to "S",
                "numOfRows" to type.maxItems.toString(),
                "pageNo" to "1",
            ),
            TourApiParser::parseList,
        )

    fun overview(contentId: String): TourResult<String> =
        get("detailCommon2", mapOf("contentId" to contentId), TourApiParser::parseOverview)

    private fun <T> get(operation: String, params: Map<String, String>, parse: (String) -> TourResult<T>): TourResult<T> {
        val query = (COMMON + params).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val url = URL("$BASE_URL/$operation?serviceKey=$encodedKey&$query")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            // 인증 오류(403 등)도 JSON 본문으로 사유를 알려주므로 errorStream 까지 읽어 파싱한다.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (body.isBlank()) TourResult.Failure("HTTP_$code", "") else parse(body)
        } catch (e: IOException) {
            TourResult.Failure("NETWORK", e.message.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://apis.data.go.kr/B551011/KorService2"
        const val TIMEOUT_MS = 8_000
        val COMMON = mapOf("MobileOS" to "AND", "MobileApp" to "YeoginEodi", "_type" to "json")
    }
}
