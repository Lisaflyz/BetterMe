package com.betterme.app.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.betterme.app.MediaItem
import com.betterme.app.MediaThumbLoader
import com.betterme.app.R
import com.betterme.app.databinding.ItemDiaryDetailMediaBinding
import com.betterme.app.diary.DiaryMedia
import com.betterme.app.diary.DiaryMediaKind
import java.util.Locale

/** Full-frame media for the read-only diary page; never starts playback automatically. */
internal class DiaryDetailMediaAdapter(
    private val media: List<DiaryMedia>,
    private val onPlay: (String) -> Unit,
    private val onPreviewPhoto: (String) -> Unit,
    private val onFirstImageSize: (Int, Int) -> Unit
) : RecyclerView.Adapter<DiaryDetailMediaAdapter.Holder>() {
    private val loader = MediaThumbLoader()
    override fun getItemCount() = media.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemDiaryDetailMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(media[position], position)
    override fun onViewRecycled(holder: Holder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    inner class Holder(private val binding: ItemDiaryDetailMediaBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DiaryMedia, position: Int) {
            val video = item.kind == DiaryMediaKind.VIDEO
            binding.image.contentDescription = if (video) binding.root.context.getString(R.string.detail_play_video)
                else binding.root.context.getString(R.string.detail_photo, position + 1, media.size)
            binding.btnPlay.setOnClickListener { onPlay(item.uri) }
            // A normal click listener lets ViewPager2 cancel taps when it intercepts a swipe.
            binding.image.setOnClickListener {
                if (video) onPlay(item.uri) else onPreviewPhoto(item.uri)
            }
            binding.duration.text = String.format(Locale.ROOT, "%d:%02d", item.durationMs / 60000, item.durationMs / 1000 % 60)
            binding.btnRetry.setOnClickListener { load(item, position) }
            load(item, position)
        }

        private fun load(item: DiaryMedia, position: Int) {
            val video = item.kind == DiaryMediaKind.VIDEO
            binding.loading.isVisible = true
            binding.errorGroup.isVisible = false
            binding.btnPlay.isVisible = false
            binding.duration.isVisible = false
            val thumbnail = MediaItem(Uri.parse(item.uri), 0L, if (video) MediaItem.Kind.VIDEO else MediaItem.Kind.PHOTO, 0L)
            loader.load(thumbnail, binding.image, sizePx = 1280) { loaded ->
                binding.loading.isVisible = false
                binding.errorGroup.isVisible = !loaded
                binding.btnPlay.isVisible = video && loaded
                binding.duration.isVisible = video && loaded
                if (loaded && position == 0) binding.image.drawable?.let {
                    // Decoded dimensions include image orientation on supported decoders.
                    onFirstImageSize(it.intrinsicWidth, it.intrinsicHeight)
                }
            }
        }

        fun clear() {
            loader.clear(binding.image)
            binding.image.setOnClickListener(null)
            binding.btnPlay.setOnClickListener(null)
            binding.btnRetry.setOnClickListener(null)
        }
    }
}
