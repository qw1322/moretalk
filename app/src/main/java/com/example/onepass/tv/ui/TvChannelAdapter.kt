package com.example.onepass.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.onepass.R
import com.example.onepass.tv.model.TvChannel

/**
 * 频道列表适配器。
 * 两列大卡片，当前正在播的台用主题紫标出，其余近黑 —— 靠颜色区分，不靠文字。
 */
class TvChannelAdapter(
    private val items: List<TvChannel>,
    private val currentId: String?,
    private val onClick: (TvChannel) -> Unit
) : RecyclerView.Adapter<TvChannelAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val nameText: TextView = view.findViewById(R.id.tvItemName)
        val groupText: TextView = view.findViewById(R.id.tvItemGroup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tv_channel, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        holder.nameText.text = channel.name
        holder.groupText.text = channel.group

        val isCurrent = channel.id == currentId
        holder.nameText.setTextColor(if (isCurrent) COLOR_CURRENT else COLOR_NORMAL)
        holder.root.alpha = if (channel.playable) 1f else 0.45f

        holder.root.setOnClickListener { onClick(channel) }
    }

    private companion object {
        const val COLOR_CURRENT = 0xFF7C3AED.toInt()
        const val COLOR_NORMAL = 0xFF1E293B.toInt()
    }
}
