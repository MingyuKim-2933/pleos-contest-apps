package com.hdcontest.roadstory.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hdcontest.roadstory.R
import com.hdcontest.roadstory.core.NarrationScript
import com.hdcontest.roadstory.core.NearbyPoi
import com.hdcontest.roadstory.core.RelativeSide

class NearbyAdapter : ListAdapter<NearbyPoi, NearbyAdapter.Holder>(Diff) {

    fun submit(items: List<NearbyPoi>) = submitList(items)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_nearby, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.nearbyName)
        private val meta: TextView = view.findViewById(R.id.nearbyMeta)

        fun bind(item: NearbyPoi) {
            name.text = item.poi.name
            val side = when (item.side) {
                RelativeSide.AHEAD -> "앞쪽"
                RelativeSide.RIGHT -> "오른쪽"
                RelativeSide.LEFT -> "왼쪽"
                RelativeSide.BEHIND -> "뒤쪽"
                RelativeSide.UNKNOWN -> ""
            }
            val distance = NarrationScript.formatDistance(item.distanceMeters)
                .replace("킬로미터", "km")
                .replace("미터", "m")
            meta.text = listOf(item.poi.category, distance, side).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    private object Diff : DiffUtil.ItemCallback<NearbyPoi>() {
        override fun areItemsTheSame(oldItem: NearbyPoi, newItem: NearbyPoi) = oldItem.poi.id == newItem.poi.id
        override fun areContentsTheSame(oldItem: NearbyPoi, newItem: NearbyPoi) = oldItem == newItem
    }
}
