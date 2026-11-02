package com.betterme.app.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.betterme.app.MediaItem
import com.betterme.app.MediaThumbLoader
import com.betterme.app.R
import com.betterme.app.databinding.ItemDailyEntryBinding
import com.betterme.app.databinding.ItemDailyPhotoBinding
import com.betterme.app.diary.DiaryEntry
import java.util.Locale

/** Entries are identified by their saved ID; updating a diary replaces its existing card. */
class DailyEntryAdapter(
    private val pagePositions: MutableMap<String, Int>,
    private val onEditEntry: (String) -> Unit,
    private val onDeleteEntry: (String) -> Unit,
    private val prunePagePositions: Boolean = true
) : ListAdapter<DiaryEntry, DailyEntryAdapter.EntryHolder>(Diff) {
    private val loader = MediaThumbLoader()

    init { stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EntryHolder(
        ItemDailyEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: EntryHolder, position: Int) = holder.bind(getItem(position))
    override fun onViewRecycled(holder: EntryHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun onCurrentListChanged(previousList: MutableList<DiaryEntry>, currentList: MutableList<DiaryEntry>) {
        super.onCurrentListChanged(previousList, currentList)
        if (prunePagePositions) pagePositions.keys.retainAll(currentList.map { it.id }.toSet())
    }

    inner class EntryHolder(private val binding: ItemDailyEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        private var entryId: String? = null
        private var photos: List<String> = emptyList()

        init {
            binding.btnMore.setOnClickListener { entryId?.let(onDeleteEntry) }
            binding.root.setOnClickListener { editEntry() }
            binding.videoCover.setOnClickListener { editEntry() }
            // Repeated pager view IDs must not restore one card's page into another card.
            // Page positions are owned by the entry-ID map instead.
            binding.photosPager.isSaveEnabled = false
            binding.photosPager.getChildAt(0).isSaveEnabled = false
            binding.photosPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    entryId?.let { pagePositions[it] = position }
                    updateCounter(position)
                }
            })
        }

        fun bind(entry: DiaryEntry) {
            binding.date.text = entry.date
            binding.title.text = entry.title
            binding.title.isVisible = entry.title.isNotBlank()
            // Bound the amount of text laid out in a scrolling card, retaining checklist markers.
            val text = entry.content.trim()
            val end = if (text.length > 500 && text[499].isHighSurrogate()) 499 else 500
            binding.summary.text = if (text.length > 500) text.take(end) + "…" else text
            binding.summary.isVisible = text.isNotEmpty()

            val playback = entry.selection.playbackUri
            val nextPhotos = if (playback == null) entry.photos else emptyList()
            binding.mediaHeader.isVisible = playback != null || nextPhotos.isNotEmpty()
            binding.photosPager.isVisible = nextPhotos.isNotEmpty()
            binding.videoCover.isVisible = playback != null
            binding.pageCounter.isVisible = nextPhotos.size > 1

            if (entryId != entry.id || photos != nextPhotos) {
                // Suppress callbacks while the recycled holder is being assigned to another entry.
                entryId = null
                photos = nextPhotos
                binding.photosPager.adapter = if (photos.isEmpty()) null else DailyPhotoAdapter(photos, loader) { editEntry() }
                val page = (pagePositions[entry.id] ?: 0).coerceIn(0, (photos.size - 1).coerceAtLeast(0))
                binding.photosPager.setCurrentItem(page, false)
                entryId = entry.id
                updateCounter(page)
            }
            binding.photosPager.isUserInputEnabled = photos.size > 1
            loader.clear(binding.videoImage)
            binding.videoMissing.isVisible = false
            binding.videoPlay.isVisible = playback != null
            if (playback != null) {
                val duration = entry.selection.durationMs.coerceAtLeast(0)
                binding.videoDuration.text = String.format(Locale.ROOT, "%d:%02d", duration / 60000, duration / 1000 % 60)
                loader.load(cardMedia(playback, MediaItem.Kind.VIDEO), binding.videoImage, cardSize(binding.root)) { loaded ->
                    binding.videoMissing.isVisible = !loaded
                    binding.videoPlay.isVisible = loaded
                }
            }
        }

        private fun editEntry() {
            entryId?.let(onEditEntry)
        }

        private fun updateCounter(position: Int) {
            if (photos.isNotEmpty()) {
                binding.pageCounter.text = binding.root.context.getString(R.string.daily_page_counter, position + 1, photos.size)
            }
        }

        fun recycle() {
            entryId = null
            photos = emptyList()
            binding.photosPager.adapter = null
            loader.clear(binding.videoImage)
        }
    }

    private object Diff : DiffUtil.ItemCallback<DiaryEntry>() {
        override fun areItemsTheSame(oldItem: DiaryEntry, newItem: DiaryEntry) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DiaryEntry, newItem: DiaryEntry) = oldItem == newItem
    }
}

/** Each holder has its own immutable page collection; recycling cannot change another card's pages. */
private class DailyPhotoAdapter(
    private val photos: List<String>,
    private val loader: MediaThumbLoader,
    private val onEdit: () -> Unit
) : RecyclerView.Adapter<DailyPhotoAdapter.PhotoHolder>() {
    override fun getItemCount() = photos.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PhotoHolder(
        ItemDailyPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun onBindViewHolder(holder: PhotoHolder, position: Int) = holder.bind(photos[position])
    override fun onViewRecycled(holder: PhotoHolder) {
        loader.clear(holder.binding.image)
        super.onViewRecycled(holder)
    }

    inner class PhotoHolder(val binding: ItemDailyPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            // ViewPager2/RecyclerView cancel the click when a swipe is intercepted.
            binding.root.setOnClickListener { onEdit() }
        }
        fun bind(uri: String) {
            binding.missing.isVisible = false
            loader.load(cardMedia(uri, MediaItem.Kind.PHOTO), binding.image, cardSize(binding.root)) { loaded ->
                binding.missing.isVisible = !loaded
            }
        }
    }
}

private fun cardMedia(uri: String, kind: MediaItem.Kind) = MediaItem(Uri.parse(uri), 0L, kind, 0L)
private fun cardSize(view: android.view.View) = view.resources.displayMetrics.widthPixels.coerceIn(384, 1080)
