package com.hdcontest.roadstory.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 한국관광공사 TourAPI 4.0 (국문 관광정보 서비스, KorService2) 응답을 다루는 순수 로직.
 * 네트워크 호출은 data/TourApiClient 가 맡고, 여기서는 파싱·변환만 해서 단위 테스트로 검증한다.
 */

/** 안내에 쓰는 관광 타입. [radiusMeters] 는 이 반경 안에 들어오면 안내를 시작하는 거리. */
enum class TourContentType(
    val code: String,
    val label: String,
    val radiusMeters: Float,
    /** locationBasedList2 조회 반경 (최대 20000m) */
    val searchRadiusMeters: Int,
    val maxItems: Int,
) {
    ATTRACTION("12", "관광지", 700f, 5_000, 20),
    CULTURE("14", "문화시설", 400f, 3_000, 10),
    SHOPPING("38", "쇼핑", 300f, 2_000, 5),
    RESTAURANT("39", "맛집", 200f, 1_500, 10);

    companion object {
        fun fromCode(code: String?): TourContentType? = entries.firstOrNull { it.code == code }
    }
}

data class TourItem(
    val contentId: String,
    val contentTypeId: String,
    val title: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

sealed interface TourResult<out T> {
    data class Success<T>(val value: T) : TourResult<T>
    data class Failure(val code: String, val message: String) : TourResult<Nothing>
}

object TourApiParser {

    private const val OK = "0000"

    /** locationBasedList2 응답 → 항목 목록. 좌표가 없는 항목은 버린다. */
    fun parseList(body: String): TourResult<List<TourItem>> = parseItems(body) { items ->
        items.mapNotNull { o ->
            val lat = o.optString("mapy").toDoubleOrNull()
            val lng = o.optString("mapx").toDoubleOrNull()
            val id = o.optString("contentid")
            val title = o.optString("title").trim()
            if (lat == null || lng == null || lat == 0.0 || lng == 0.0 || id.isBlank() || title.isBlank()) {
                null
            } else {
                TourItem(
                    contentId = id,
                    contentTypeId = o.optString("contenttypeid"),
                    title = title,
                    address = listOf(o.optString("addr1"), o.optString("addr2"))
                        .filter { it.isNotBlank() }.joinToString(" "),
                    latitude = lat,
                    longitude = lng,
                )
            }
        }
    }

    /** detailCommon2 응답 → 개요(overview) 원문. 없으면 빈 문자열. */
    fun parseOverview(body: String): TourResult<String> = parseItems(body) { items ->
        items.firstOrNull()?.optString("overview").orEmpty()
    }

    private fun <T> parseItems(body: String, map: (List<JSONObject>) -> T): TourResult<T> {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return TourResult.Failure("PARSE", body.take(120).trim())
        }

        // 인증키 오류 등 게이트웨이 오류: {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{...}}}
        root.optJSONObject("OpenAPI_ServiceResponse")?.optJSONObject("cmmMsgHeader")?.let { h ->
            return TourResult.Failure(
                h.optString("returnReasonCode", "ERR"),
                h.optString("returnAuthMsg").ifBlank { h.optString("errMsg") },
            )
        }

        val response = root.optJSONObject("response")
            ?: return TourResult.Failure("PARSE", body.take(120).trim())
        val header = response.optJSONObject("header")
        val code = header?.optString("resultCode").orEmpty()
        if (code != OK) return TourResult.Failure(code.ifBlank { "ERR" }, header?.optString("resultMsg").orEmpty())

        // 결과가 없으면 items 가 빈 문자열(""), 하나면 item 이 객체로 올 수 있어 모두 처리한다.
        val items = response.optJSONObject("body")?.optJSONObject("items")
        val list = when (val item = items?.opt("item")) {
            is JSONArray -> (0 until item.length()).mapNotNull { item.optJSONObject(it) }
            is JSONObject -> listOf(item)
            else -> emptyList()
        }
        return TourResult.Success(map(list))
    }
}

/** TourAPI 의 긴 개요 글을 운전 중에 듣기 좋은 길이로 줄인다. */
object OverviewSummarizer {

    private val tag = Regex("<[^>]+>")
    private val hanjaParen = Regex("\\s*\\([^)]*[\\u4E00-\\u9FFF][^)]*\\)")
    private val whitespace = Regex("\\s+")
    private val sentenceEnd = Regex("(?<=[.!?。])\\s+")

    fun summarize(raw: String, maxChars: Int = 150): String {
        val text = decodeEntities(raw.replace(tag, " "))
            .replace(hanjaParen, "") // TTS 가 한자를 어색하게 읽으므로 "경복궁(景福宮)" 의 괄호는 뺀다
            .replace(whitespace, " ")
            .trim()
        if (text.isEmpty()) return ""

        val sentences = text.split(sentenceEnd).filter { it.isNotBlank() }
        val out = StringBuilder()
        for (s in sentences) {
            if (out.isNotEmpty() && out.length + s.length + 1 > maxChars) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(s)
        }
        if (out.length <= maxChars + 40) return out.toString()

        // 첫 문장부터 너무 길면 단어 경계에서 자른다.
        val cut = out.substring(0, maxChars).substringBeforeLast(' ').ifBlank { out.substring(0, maxChars) }
        return "$cut…"
    }

    private fun decodeEntities(s: String) = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

object TourPoiMapper {

    const val ID_PREFIX = "tour-"
    private val trailingParen = Regex("\\s*\\([^)]*\\)\\s*$")

    fun toPoi(item: TourItem, overview: String?): Poi? {
        val type = TourContentType.fromCode(item.contentTypeId) ?: return null
        val story = overview?.let(OverviewSummarizer::summarize).orEmpty()
            .ifBlank { "한국관광공사에 등록된 ${type.label}${NarrationScript.copula(type.label)}." }
        return Poi(
            id = ID_PREFIX + item.contentId,
            // "신해철거리(성남)" 처럼 끝에 붙은 지역 괄호는 TTS 가 그대로 읽으므로 뺀다.
            name = item.title.replace(trailingParen, "").ifBlank { item.title },
            category = type.label,
            latitude = item.latitude,
            longitude = item.longitude,
            radiusMeters = type.radiusMeters,
            address = item.address,
            story = story,
        )
    }
}

/**
 * 직접 검수한 내장 장소(pois.json)와 TourAPI 장소를 합친다.
 * 같은 장소로 보이면 내장 장소를 남긴다 (이야기를 운전용으로 다듬어 두었기 때문).
 */
object PoiMerger {

    private const val SAME_PLACE_METERS = 80.0
    private const val SIMILAR_NAME_METERS = 600.0

    fun merge(local: List<Poi>, remote: List<Poi>): List<Poi> {
        val result = local.toMutableList()
        val seen = local.map { it.id }.toMutableSet()
        for (poi in remote) {
            if (!seen.add(poi.id)) continue
            if (result.any { isSamePlace(it, poi) }) continue
            result += poi
        }
        return result
    }

    fun isSamePlace(a: Poi, b: Poi): Boolean {
        val d = GeoUtils.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        if (d <= SAME_PLACE_METERS) return true
        if (d > SIMILAR_NAME_METERS) return false
        val na = normalize(a.name)
        val nb = normalize(b.name)
        return na.isNotEmpty() && nb.isNotEmpty() && (na.contains(nb) || nb.contains(na))
    }

    private fun normalize(name: String) = name.replace(Regex("[\\s()\\[\\]·.,\\-]"), "").lowercase()
}

/** 차량이 일정 거리 이상 움직였거나 시간이 지났을 때만 다시 조회한다 (호출량 제한 대응). */
class TourFetchPolicy(
    private val refetchDistanceMeters: Double = 2_000.0,
    private val refetchIntervalMs: Long = 30 * 60 * 1000L,
    private val retryAfterFailureMs: Long = 60 * 1000L,
) {
    private var lastCenter: GeoPoint? = null
    private var lastSuccessMs = 0L
    private var lastFailureMs: Long? = null

    fun shouldFetch(at: GeoPoint, nowMs: Long): Boolean {
        lastFailureMs?.let { if (nowMs - it < retryAfterFailureMs) return false }
        val center = lastCenter ?: return true
        if (nowMs - lastSuccessMs >= refetchIntervalMs) return true
        return GeoUtils.distanceMeters(center.latitude, center.longitude, at.latitude, at.longitude) >= refetchDistanceMeters
    }

    fun onSuccess(at: GeoPoint, nowMs: Long) {
        lastCenter = at
        lastSuccessMs = nowMs
        lastFailureMs = null
    }

    fun onFailure(nowMs: Long) {
        lastFailureMs = nowMs
    }
}

/** TourAPI 쪽 오류 코드를 사용자에게 보여줄 문장으로 바꾼다. */
object TourErrorMessages {
    fun of(failure: TourResult.Failure): String = when (failure.code) {
        "30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR" -> "TourAPI 인증키가 등록되지 않았어요. 키를 확인해 주세요."
        "22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR" -> "TourAPI 하루 호출 한도를 넘었어요. 내장 명소로 안내할게요."
        "NETWORK" -> "인터넷에 연결할 수 없어 내장 명소로 안내할게요."
        else -> "관광 정보를 불러오지 못했어요 (${failure.code}). 내장 명소로 안내할게요."
    }
}
