package com.betterme.app.ui

import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.betterme.app.R

/** One footer shared by the daily feed and the expanded growth section. */
class DiaryPageAdapter(private val retry: () -> Unit) : RecyclerView.Adapter<DiaryPageAdapter.Holder>() {
    private var visible = false
    private var busy = false
    private var failed = false

    fun update(hasMore: Boolean, loading: Boolean, error: Boolean) {
        val old = visible
        visible = hasMore || loading || error
        busy = loading
        failed = error
        when {
            !old && visible -> notifyItemInserted(0)
            old && !visible -> notifyItemRemoved(0)
            visible -> notifyItemChanged(0)
        }
    }

    override fun getItemCount() = if (visible) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(Button(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { retry() }
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.button.setText(
            when {
                busy -> R.string.diary_page_loading
                failed -> R.string.diary_page_retry
                else -> R.string.diary_page_more
            }
        )
        holder.button.isEnabled = !busy
    }
    class Holder(val button: Button) : RecyclerView.ViewHolder(button)
}
