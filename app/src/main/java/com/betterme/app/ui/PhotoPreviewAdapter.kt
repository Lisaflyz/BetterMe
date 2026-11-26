package com.betterme.app.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.betterme.app.MediaThumbLoader
import com.betterme.app.databinding.ItemPhotoPreviewBinding

internal class PhotoPreviewAdapter(
    private val photos: List<String>
) : RecyclerView.Adapter<PhotoPreviewAdapter.Holder>() {
    private val loader = MediaThumbLoader()
    private val boundHolders = mutableSetOf<Holder>()

    override fun getItemCount() = photos.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemPhotoPreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        boundHolders.add(holder)
        holder.bind(photos[position])
    }

    override fun onViewRecycled(holder: Holder) {
        holder.clear()
        boundHolders.remove(holder)
        super.onViewRecycled(holder)
    }

    fun clear() {
        boundHolders.forEach { it.clear() }
        boundHolders.clear()
    }

    inner class Holder(private val binding: ItemPhotoPreviewBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(photo: String) {
            binding.photoView.allowPagingWhenFit = photos.size > 1
            binding.btnRetry.setOnClickListener { load(photo) }
            load(photo)
        }

        private fun load(photo: String) {
            binding.loading.isVisible = true
            binding.errorPanel.isVisible = false
            val uri = photo.takeIf { it.isNotBlank() }?.let(Uri::parse)
            loader.loadPreview(uri, binding.photoView) { loaded ->
                binding.loading.isVisible = false
                binding.errorPanel.isVisible = !loaded
            }
        }

        fun clear() {
            loader.clear(binding.photoView)
            binding.btnRetry.setOnClickListener(null)
        }
    }
}
