package com.betterme.app

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.target.BitmapImageViewTarget
import com.bumptech.glide.request.transition.Transition

/** Glide owns decoding, caching and request replacement; callbacks run after the image is displayed. */
class MediaThumbLoader {
    fun load(item: MediaItem, imageView: ImageView, sizePx: Int = 384, onLoaded: ((Boolean) -> Unit)? = null) {
        clear(imageView)
        val size = sizePx.coerceIn(128, 1280)
        var request = Glide.with(imageView).asBitmap().load(item.localUri ?: item.uri)
            .override(size, size).fitCenter().dontAnimate()
        if (item.kind == MediaItem.Kind.VIDEO) request = request.frame(0L)
        request.into(callbackTarget(imageView, onLoaded))
    }

    /** Fit within 2048px while keeping the original aspect ratio and EXIF orientation. */
    fun loadPreview(uri: Uri?, imageView: ZoomImageView, onLoaded: (Boolean) -> Unit) {
        clear(imageView)
        val request: RequestBuilder<Bitmap> = Glide.with(imageView).asBitmap().load(uri)
            .override(2048, 2048).fitCenter().dontAnimate()
        request.into(callbackTarget(imageView, onLoaded))
    }

    private fun callbackTarget(imageView: ImageView, onLoaded: ((Boolean) -> Unit)?) =
        object : BitmapImageViewTarget(imageView) {
            override fun setResource(resource: Bitmap?) {
                if (imageView is ZoomImageView) imageView.setPreviewBitmap(resource)
                else super.setResource(resource)
            }

            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                super.onResourceReady(resource, transition)
                onLoaded?.invoke(true)
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                super.onLoadFailed(errorDrawable)
                onLoaded?.invoke(false)
            }
        }

    fun clear(imageView: ImageView) {
        // Clearing through the application manager also works during Activity destruction.
        Glide.with(imageView.context.applicationContext).clear(imageView)
        imageView.setImageDrawable(null)
    }

    /** Release requests before removing a dynamically created media subtree. */
    fun clearViews(view: View) {
        if (view is ImageView) clear(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) clearViews(view.getChildAt(index))
    }
}
