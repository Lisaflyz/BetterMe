package com.betterme.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.betterme.app.databinding.ItemMediaCellBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 相册里的一个媒体项（照片或视频）。 */
data class MediaItem(
    val uri: Uri,
    val id: Long,
    val kind: Kind,
    val dateAdded: Long,
    val durationMs: Long = 0L,
    val bucketId: String = "",
    val bucketName: String = "相册",
    val localUri: Uri? = null
) {
    enum class Kind { PHOTO, VIDEO }
}

/** Immutable UI snapshot: -1 means unselected; selection order must participate in diffing. */
data class AlbumCell(val media: MediaItem, val selectionOrder: Int = -1)

internal object AlbumSelectionChanged

internal object AlbumCellDiff : DiffUtil.ItemCallback<AlbumCell>() {
    // Images and videos can have the same numeric MediaStore ID; use the full URI.
    override fun areItemsTheSame(oldItem: AlbumCell, newItem: AlbumCell) =
        oldItem.media.uri == newItem.media.uri

    override fun areContentsTheSame(oldItem: AlbumCell, newItem: AlbumCell) = oldItem == newItem

    override fun getChangePayload(oldItem: AlbumCell, newItem: AlbumCell): Any? =
        if (oldItem.media == newItem.media) AlbumSelectionChanged else null
}

/** Selection payloads only update the badge; thumbnails and live-photo jobs stay untouched. */
class AlbumAdapter(
    private val cellSizePx: Int,
    private val scope: CoroutineScope,
    private val onSelect: (MediaItem) -> Unit,
    private val onPreview: (MediaItem) -> Unit
) : ListAdapter<AlbumCell, AlbumAdapter.VH>(AlbumCellDiff) {
    private val loader = MediaThumbLoader()
    private var motionDetector: MotionPhotoDetector? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.cellRoot.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSizePx)
        return VH(binding).also { holder ->
            binding.cellRoot.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onPreview(getItem(position).media)
            }
            binding.tvBadge.isFocusable = true
            binding.tvBadge.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onSelect(getItem(position).media)
            }
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cell = getItem(position)
        val item = cell.media
        holder.motionJob?.cancel()
        holder.binding.liveTag.isVisible = false
        if (item.kind == MediaItem.Kind.PHOTO) {
            val detector = motionDetector ?: MotionPhotoDetector(holder.itemView.context).also { motionDetector = it }
            holder.motionJob = scope.launch {
                holder.binding.liveTag.isVisible = detector.isMotionPhoto(item.localUri ?: item.uri)
            }
        }
        bindSelection(holder, cell)
        holder.binding.videoTag.isVisible = item.kind == MediaItem.Kind.VIDEO
        if (item.kind == MediaItem.Kind.VIDEO) holder.binding.tvDuration.text = formatDuration(item.durationMs)
        loader.load(item, holder.binding.ivThumb)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === AlbumSelectionChanged }) {
            bindSelection(holder, getItem(position))
        } else {
            // New/recycled/offscreen holders and media changes still need a complete bind.
            onBindViewHolder(holder, position)
        }
    }

    private fun bindSelection(holder: VH, cell: AlbumCell) {
        val order = cell.selectionOrder
        holder.binding.tvBadge.isVisible = true
        holder.binding.tvBadge.text = if (order >= 0) (order + 1).toString() else ""
        holder.binding.tvBadge.setBackgroundResource(if (order >= 0) R.drawable.album_checked else R.drawable.album_unchecked)
        holder.binding.tvBadge.contentDescription = if (order >= 0) "取消选择第${order + 1}项" else "选择此素材"
        holder.binding.cellRoot.contentDescription = "预览${if (cell.media.kind == MediaItem.Kind.VIDEO) "视频" else "照片"}，${if (order >= 0) "已选第${order + 1}项" else "未选择"}"
    }

    override fun onViewRecycled(holder: VH) {
        holder.motionJob?.cancel()
        holder.motionJob = null
        holder.binding.liveTag.isVisible = false
        loader.clear(holder.binding.ivThumb)
        super.onViewRecycled(holder)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    class VH(val binding: ItemMediaCellBinding) : RecyclerView.ViewHolder(binding.root) {
        var motionJob: Job? = null
    }
}
