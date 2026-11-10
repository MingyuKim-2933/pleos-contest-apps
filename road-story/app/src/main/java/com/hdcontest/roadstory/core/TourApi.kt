package com.hdcontest.roadstory.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 한국관광공사 TourAPI 4.0 (국문 관광정보 서비스, KorService2) 응답을 다루는 순수 로직.
 * 네트워크 호출은 data/TourApiClient 가 맡고, 여기서는 파싱·변환만 해서 단위 테스트로 검증한다.
 */

/**
 * 안내에 쓰는 관광 타입.
 * [radiusMeters] 는 기본 안내 거리 (실제로는 속도에 따라 더 멀리서 시작한다, [TriggerDistance]).
 */
enum class TourContentType(
    val code: String,
    val label: String,
    val radiusMeters: Float,
    /** locationBasedList2 조회 반경 (최대 20000m) */
    val searchRadiusMeters: Int,
    val maxItems: Int,
    /** 조회 여부. 개별 음식점은 대부분 지역 소규모 매장이라 기본으로 조회하지 않는다. */
    val queried: Boolean = true,
) {
    ATTRACTION("12", "관광지", 1_500f, 10_000, 40),
    CULTURE("14", "문화시설", 1_000f, 5_000, 20),
    SHOPPING("38", "쇼핑", 1_000f, 5_000, 10),
    RESTAURANT("39", "맛집", 800f, 3_000, 20, queried = false);

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
    /** 관광 분류체계 코드 (lclsSystm3, 예: HS010100). 앞 4자리가 중분류. */
    val classCode: String = "",
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
                    classCode = o.optString("lclsSystm3").ifBlank { o.optString("lclsSystm2") },
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

/**
 * 운전자가 이름만 듣고도 바로 알 만한 장소만 고른다.
 *
 * TourAPI 에는 동네 근린공원·도서관·산업시설 견학 같은 곳도 관광지로 등록되어 있어,
 * 분류체계(lclsSystm) 중분류로 걸러 역사 유적·자연 명소·랜드마크·백화점·전통시장·음식 거리 위주로 남긴다.
 */
object TourNotability {

    private val labels = mapOf(
        "HS01" to "역사 유적", "HS02" to "역사 유적", "HS03" to "종교 성지", "HS04" to "안보 관광지",
        // 자연공원(NA04)은 동네 도시자연공원이 많아 제외한다.
        "NA01" to "산", "NA02" to "강·호수·바다",
        "VE01" to "랜드마크", "VE02" to "테마파크", "VE05" to "복합 관광지", "VE06" to "공연장", "VE07" to "전시관",
        "SH01" to "백화점", "SH02" to "쇼핑몰", "SH04" to "면세점", "SH06" to "전통시장",
    )

    /** 도시·지역문화관광(VE04) 가운데 음식 거리만 남긴다 (예: 먹자골목, 닭죽촌, 음식마을). */
    private const val FOOD_STREET = "VE04"
    private val foodStreetName = Regex("맛집|먹자|음식|골목|촌|카페거리|시장")

    fun categoryOf(item: TourItem): String? {
        val mid = item.classCode.take(4)
        labels[mid]?.let { return it }
        if (mid == FOOD_STREET && foodStreetName.containsMatchIn(item.title)) return "맛집 거리"
        return null
    }

    fun isNotable(item: TourItem) = categoryOf(item) != null
}

object TourPoiMapper {

    const val ID_PREFIX = "tour-"
    private val trailingParen = Regex("\\s*\\([^)]*\\)\\s*$")

    /** 이름만으로 알기 어려운 장소(근린공원, 도서관 등)는 null. */
    fun toPoi(item: TourItem, overview: String?): Poi? {
        val type = TourContentType.fromCode(item.contentTypeId) ?: return null
        val category = TourNotability.categoryOf(item) ?: return null
        val story = overview?.let(OverviewSummarizer::summarize).orEmpty()
            .ifBlank { "한국관광공사에 등록된 ${category}${NarrationScript.copula(category)}." }
        return Poi(
            id = ID_PREFIX + item.contentId,
            // "신해철거리(성남)" 처럼 끝에 붙은 지역 괄호는 TTS 가 그대로 읽으므로 뺀다.
            name = item.title.replace(trailingParen, "").ifBlank { item.title },
            category = category,
            latitude = item.latitude,
            longitude = item.longitude,
            radiusMeters = type.radiusMeters,
            address = item.address,
            story = story,
            prominence = Poi.LISTED,
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
