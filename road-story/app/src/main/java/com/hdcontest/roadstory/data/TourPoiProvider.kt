package com.hdcontest.roadstory.data

import android.content.Context
import android.util.Log
import com.hdcontest.roadstory.core.GeoPoint
import com.hdcontest.roadstory.core.GeoUtils
import com.hdcontest.roadstory.core.Poi
import com.hdcontest.roadstory.core.TourContentType
import com.hdcontest.roadstory.core.TourErrorMessages
import com.hdcontest.roadstory.core.TourFetchPolicy
import com.hdcontest.roadstory.core.TourItem
import com.hdcontest.roadstory.core.TourPoiMapper
import com.hdcontest.roadstory.core.TourResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 차량 주변의 관광지·문화시설·쇼핑·맛집을 TourAPI 에서 받아 [Poi] 로 넘겨준다.
 *
 * - 2km 이상 이동했거나 30분이 지나면 다시 조회한다 ([TourFetchPolicy]).
 * - 가까운 곳부터 개요(이야기)를 받고, 개요를 받은 장소만 안내 대상으로 넘긴다.
 *   (목록만 먼저 넘기면 개요가 오기 전에 진입한 장소를 빈 이야기로 안내하게 된다)
 * - 마지막 결과를 파일에 저장해 두어 통신이 끊겨도 직전 지역은 안내할 수 있다.
 *
 * 메인 스레드에서 호출하고, 콜백도 메인 스레드로 전달한다.
 */
class TourPoiProvider(
    context: Context,
    serviceKey: String,
    private val onPois: (List<Poi>) -> Unit,
    private val onError: (String) -> Unit,
) {
    val enabled = serviceKey.isNotBlank()

    private val client = TourApiClient(serviceKey)
    private val cacheFile = File(context.filesDir, CACHE_FILE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val policy = TourFetchPolicy()

    private var items = linkedMapOf<String, TourItem>()
    private val overviews = mutableMapOf<String, String>()
    private var job: Job? = null
    private var lastErrorCode: String? = null

    /** 저장해 둔 직전 결과를 불러온다. */
    fun restore() {
        if (!enabled) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { readCache() }.getOrNull() }
            if (loaded != null && items.isEmpty()) {
                items = loaded.first
                overviews.putAll(loaded.second)
                publish()
            }
        }
    }

    fun onLocation(latitude: Double, longitude: Double) {
        if (!enabled || job?.isActive == true) return
        val at = GeoPoint(latitude, longitude)
        if (!policy.shouldFetch(at, System.currentTimeMillis())) return
        job = scope.launch { refresh(at) }
    }

    fun release() = scope.cancel()

    private suspend fun refresh(at: GeoPoint) {
        Log.i(TAG, "TourAPI 조회 시작: ${at.latitude}, ${at.longitude}")
        val results = withContext(Dispatchers.IO) {
            TourContentType.entries.map { type ->
                client.locationBasedList(at.latitude, at.longitude, type).also {
                    Log.d(TAG, "${type.label}: ${(it as? TourResult.Success)?.value?.size ?: it}")
                }
            }
        }
        val fetched = results.filterIsInstance<TourResult.Success<List<TourItem>>>().flatMap { it.value }
        val failure = results.filterIsInstance<TourResult.Failure>().firstOrNull()
        if (fetched.isEmpty() && failure != null) {
            policy.onFailure(System.currentTimeMillis())
            Log.w(TAG, "TourAPI 조회 실패: ${failure.code} ${failure.message}")
            // 같은 오류를 반복해서 띄우지 않는다.
            if (failure.code != lastErrorCode) onError(TourErrorMessages.of(failure))
            lastErrorCode = failure.code
            return
        }
        policy.onSuccess(at, System.currentTimeMillis())
        lastErrorCode = null

        // 새로 받은 장소를 더하고, 너무 멀어진 장소는 정리한다.
        val merged = linkedMapOf<String, TourItem>()
        (items.values + fetched).forEach { merged[it.contentId] = it }
        items = merged.filterValues { distance(at, it) <= KEEP_RADIUS_METERS }.toMap(linkedMapOf())
        publish()

        // 가까운 곳부터 개요를 받아 이야기를 채운다.
        val missing = items.values
            .filter { it.contentId !in overviews }
            .sortedBy { distance(at, it) }
            .take(MAX_OVERVIEWS_PER_ROUND)
        if (missing.isNotEmpty()) {
            val received = withContext(Dispatchers.IO) {
                missing.mapNotNull { item ->
                    (client.overview(item.contentId) as? TourResult.Success)?.let { item.contentId to it.value }
                }
            }
            overviews.putAll(received)
            publish()
        }
        Log.i(TAG, "TourAPI: 주변 ${items.size}곳, 개요 ${overviews.size}개")

        val snapshotItems = items.values.toList()
        val snapshotOverviews = overviews.toMap()
        withContext(Dispatchers.IO) { runCatching { writeCache(snapshotItems, snapshotOverviews) } }
    }

    private fun publish() {
        onPois(
            items.values
                .filter { it.contentId in overviews }
                .mapNotNull { TourPoiMapper.toPoi(it, overviews[it.contentId]) },
        )
    }

    private fun distance(at: GeoPoint, item: TourItem) =
        GeoUtils.distanceMeters(at.latitude, at.longitude, item.latitude, item.longitude)

    // region 파일 캐시

    private fun writeCache(items: List<TourItem>, overviews: Map<String, String>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("contentId", it.contentId)
                    .put("contentTypeId", it.contentTypeId)
                    .put("title", it.title)
                    .put("address", it.address)
                    .put("latitude", it.latitude)
                    .put("longitude", it.longitude)
                    .put("overview", overviews[it.contentId]),
            )
        }
        cacheFile.writeText(JSONObject().put("items", array).toString())
    }

    private fun readCache(): Pair<LinkedHashMap<String, TourItem>, Map<String, String>>? {
        if (!cacheFile.exists()) return null
        val array = JSONObject(cacheFile.readText()).getJSONArray("items")
        val items = linkedMapOf<String, TourItem>()
        val overviews = mutableMapOf<String, String>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val item = TourItem(
                contentId = o.getString("contentId"),
                contentTypeId = o.getString("contentTypeId"),
                title = o.getString("title"),
                address = o.optString("address"),
                latitude = o.getDouble("latitude"),
                longitude = o.getDouble("longitude"),
            )
            items[item.contentId] = item
            if (o.has("overview") && !o.isNull("overview")) overviews[item.contentId] = o.getString("overview")
        }
        return items to overviews
    }

    // endregion

    private companion object {
        const val TAG = "TourPoiProvider"
        const val CACHE_FILE = "tour_cache.json"
        const val KEEP_RADIUS_METERS = 15_000.0
        const val MAX_OVERVIEWS_PER_ROUND = 20
    }
}
