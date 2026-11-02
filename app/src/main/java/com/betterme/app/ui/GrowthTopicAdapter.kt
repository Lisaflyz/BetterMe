package com.betterme.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.betterme.app.R
import com.betterme.app.databinding.ItemGrowthTopicBinding

/** A single topic header; ConcatAdapter inserts the shared article adapter below the open header. */
internal class GrowthTopicAdapter(
    val key: String,
    private val onToggle: (String) -> Unit
) : RecyclerView.Adapter<GrowthTopicAdapter.Holder>() {
    private var name = ""
    private var count = 0
    private var expanded = false

    fun update(name: String, count: Int, expanded: Boolean) {
        if (this.name == name && this.count == count && this.expanded == expanded) return
        this.name = name
        this.count = count
        this.expanded = expanded
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemGrowthTopicBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind()

    inner class Holder(private val binding: ItemGrowthTopicBinding) : RecyclerView.ViewHolder(binding.root) {
        init { binding.root.setOnClickListener { onToggle(key) } }
        fun bind() {
            binding.topic.text = name
            binding.count.text = binding.root.context.getString(R.string.growth_topic_count, count)
            binding.arrow.rotation = if (expanded) 180f else 0f
            binding.root.contentDescription = binding.root.context.getString(
                if (expanded) R.string.growth_topic_collapse else R.string.growth_topic_expand, name, count
            )
        }
    }
}
