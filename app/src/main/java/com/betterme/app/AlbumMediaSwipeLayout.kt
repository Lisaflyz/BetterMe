package com.betterme.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

/** Intercepts deliberate horizontal swipes, leaving taps and vertical grid scrolling to children. */
class AlbumMediaSwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    /** Left swipe = +1 (next tab), right swipe = -1 (previous tab). */
    var onSwipe: ((Int) -> Unit)? = null

    private enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL, IGNORED }
    private var axis = Axis.UNDECIDED
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minSwipeDistance = max(touchSlop * 3, 48 * resources.displayMetrics.density)

    private fun begin(event: MotionEvent) {
        downX = event.x
        downY = event.y
        axis = Axis.UNDECIDED
    }

    private fun resolveAxis(event: MotionEvent) {
        if (axis != Axis.UNDECIDED) return
        val dx = abs(event.x - downX)
        val dy = abs(event.y - downY)
        if (dy > touchSlop && dy >= dx) {
            axis = Axis.VERTICAL
        } else if (dx > touchSlop && dx > dy * 1.25f) {
            axis = Axis.HORIZONTAL
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event)
            MotionEvent.ACTION_POINTER_DOWN -> axis = Axis.IGNORED
            MotionEvent.ACTION_MOVE -> {
                resolveAxis(event)
                // RecyclerView receives CANCEL, so swiping cannot also select a thumbnail.
                if (axis == Axis.HORIZONTAL) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> axis = Axis.UNDECIDED
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event) // Also allow swipes when the grid is empty.
            MotionEvent.ACTION_POINTER_DOWN -> axis = Axis.IGNORED
            MotionEvent.ACTION_MOVE -> resolveAxis(event)
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (axis == Axis.HORIZONTAL && abs(dx) >= minSwipeDistance && abs(dx) > abs(dy) * 1.25f) {
                    onSwipe?.invoke(if (dx < 0) 1 else -1)
                } else if (axis == Axis.UNDECIDED) {
                    performClick()
                }
                axis = Axis.UNDECIDED
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                axis = Axis.UNDECIDED
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
