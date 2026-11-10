package com.hdcontest.roadstory

import android.content.Context
import com.hdcontest.roadstory.core.Poi
import org.json.JSONArray

/** assets/pois.json 에서 안내 장소를 읽는다. */
object PoiRepository {

    fun load(context: Context, assetName: String = "pois.json"): List<Poi> {
        val json = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Poi(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.optString("category", ""),
                latitude = o.getDouble("latitude"),
                longitude = o.getDouble("longitude"),
                radiusMeters = o.optDouble("radiusMeters", 500.0).toFloat(),
                address = o.optString("address", ""),
                story = o.getString("story"),
                prominence = Poi.CURATED,
            )
        }
    }
}
