package com.betterme.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** Reuse selected thumbnails when adding, removing or reordering items. */
class AlbumSelectedAdapter(
    private val onRemove: (MediaItem) -> Unit,
    private val onPreview: (MediaItem) -> Unit,
    private val showVideoDuration: Boolean = false
) :
    ListAdapter<AlbumCell, AlbumSelectedAdapter.VH>(AlbumCellDiff) {
    private val loader = MediaThumbLoader()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val context = parent.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val tile = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(dp(74), dp(74)).apply { marginEnd = dp(14) }
            clipChildren = false
            clipToPadding = false
        }
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_album_selected_thumb)
            clipToOutline = true
        }
        tile.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        val duration = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_video_time)
            setPadding(dp(4), 0, dp(4), 0)
            isVisible = false
        }
        tile.addView(duration, FrameLayout.LayoutParams(-2, dp(22), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(4)
        })
        // Keep the 6dp glyph and overflowing round badge, with a larger touch target.
        val remove = FrameLayout(context).apply {
            isFocusable = true
            clipChildren = false
            clipToPadding = false
        }
        val badge = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_album_remove)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        badge.addView(ImageView(context).apply { setImageResource(R.drawable.ic_album_remove) },
            FrameLayout.LayoutParams(dp(6), dp(6), Gravity.CENTER))
        remove.addView(badge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
            topMargin = -dp(6)
            marginEnd = -dp(6)
        })
        // Keep removal in the corner; the thumbnail body always opens a preview.
        tile.addView(remove, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END))
        return VH(tile, thumb, remove, duration).also { holder ->
            tile.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onPreview(getItem(position).media)
            }
            remove.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onRemove(getItem(position).media)
            }
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cell = getItem(position)
        bindOrder(holder, cell)
        holder.duration.isVisible = showVideoDuration && cell.media.kind == MediaItem.Kind.VIDEO
        if (holder.duration.isVisible) {
            val seconds = cell.media.durationMs / 1000
            holder.duration.text = String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
        loader.load(cell.media, holder.thumb)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === AlbumSelectionChanged }) {
            bindOrder(holder, getItem(position))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private fun bindOrder(holder: VH, cell: AlbumCell) {
        holder.remove.contentDescription = "移除第${cell.selectionOrder + 1}项"
        holder.itemView.contentDescription = "预览第${cell.selectionOrder + 1}项${if (cell.media.kind == MediaItem.Kind.VIDEO) "视频" else "图片"}"
    }

    override fun onViewRecycled(holder: VH) {
        loader.clear(holder.thumb)
        super.onViewRecycled(holder)
    }

    class VH(root: FrameLayout, val thumb: ImageView, val remove: FrameLayout, val duration: TextView) : RecyclerView.ViewHolder(root)
}
