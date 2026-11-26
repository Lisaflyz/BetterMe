package com.betterme.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

/** Fits the whole photo initially; scales around the fingers and clamps panning to the image edges. */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    var allowPagingWhenFit = false
    private var pagingBlockedForGesture = false
    private val transform = Matrix()
    private val imageBounds = RectF()
    private var zoom = 1f
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var clickCandidate = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var focusX = 0f
            private var focusY = 0f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                clickCandidate = false
                focusX = detector.focusX
                focusY = detector.focusY
                return drawable != null && width > 0 && height > 0
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (!factor.isFinite() || factor <= 0f) return false
                val next = (zoom * factor).coerceIn(1f, 5f)
                transform.postScale(next / zoom, next / zoom, focusX, focusY)
                transform.postTranslate(detector.focusX - focusX, detector.focusY - focusY)
                zoom = next
                focusX = detector.focusX
                focusY = detector.focusY
                constrainImage()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (zoom <= 1.001f) resetToFit() else constrainImage()
            }
        })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        scaleDetector.isQuickScaleEnabled = false
    }

    fun setPreviewBitmap(bitmap: Bitmap?) {
        if (bitmap == null) setImageDrawable(null) else setImageBitmap(bitmap)
        resetToFit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetToFit()
    }

    private fun resetToFit() {
        zoom = 1f
        transform.reset()
        val image = drawable
        if (image != null && image.intrinsicWidth > 0 && image.intrinsicHeight > 0 && width > 0 && height > 0) {
            val scale = min(width.toFloat() / image.intrinsicWidth, height.toFloat() / image.intrinsicHeight)
            transform.setScale(scale, scale)
            transform.postTranslate((width - image.intrinsicWidth * scale) / 2f,
                (height - image.intrinsicHeight * scale) / 2f)
        }
        imageMatrix = transform
    }

    private fun constrainImage() {
        val image = drawable ?: return
        imageBounds.set(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
        transform.mapRect(imageBounds)
        val dx = when {
            imageBounds.width() <= width -> width / 2f - imageBounds.centerX()
            imageBounds.left > 0f -> -imageBounds.left
            imageBounds.right < width -> width - imageBounds.right
            else -> 0f
        }
        val dy = when {
            imageBounds.height() <= height -> height / 2f - imageBounds.centerY()
            imageBounds.top > 0f -> -imageBounds.top
            imageBounds.bottom < height -> height - imageBounds.bottom
            else -> 0f
        }
        transform.postTranslate(dx, dy)
        imageMatrix = transform
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || drawable == null) return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pagingBlockedForGesture = zoom > 1.001f
                activePointer = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                clickCandidate = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pagingBlockedForGesture = true
                activePointer = MotionEvent.INVALID_POINTER_ID
                clickCandidate = false
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (event.pointerCount == 1 && index >= 0 && !scaleDetector.isInProgress) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (allowPagingWhenFit && !pagingBlockedForGesture &&
                        abs(x - downX) > touchSlop && abs(x - downX) > abs(y - downY)) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) clickCandidate = false
                    if (zoom > 1f && !clickCandidate) {
                        transform.postTranslate(x - lastX, y - lastY)
                        constrainImage()
                    }
                    lastX = x
                    lastY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Reset the one-finger origin after a pinch, even if the original pointer was lifted.
                if (event.pointerCount == 2) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    activePointer = event.getPointerId(remaining)
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && clickCandidate) performClick()
                activePointer = MotionEvent.INVALID_POINTER_ID
                clickCandidate = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (zoom <= 1.001f) resetToFit() else constrainImage()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
