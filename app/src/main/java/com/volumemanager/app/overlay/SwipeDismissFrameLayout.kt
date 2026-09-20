package com.volumemanager.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Root overlay host: intercepts dominant horizontal swipes to the right to dismiss.
 * Vertical gestures are left to child volume sliders.
 */
class SwipeDismissFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onSwipeDismiss: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dismissDistance = resources.displayMetrics.density * 80f
    private var downX = 0f
    private var downY = 0f
    private var trackingHorizontal = false
    private var dragging = false
    private var panel: android.view.View? = null

    private fun panelView(): android.view.View? {
        if (panel == null) {
            panel = findViewById(com.volumemanager.app.R.id.overlayPanel)
        }
        return panel
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                trackingHorizontal = false
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!trackingHorizontal && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    trackingHorizontal = abs(dx) > abs(dy) && dx > 0f
                }
                if (trackingHorizontal) return true
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = panelView() ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!trackingHorizontal) return true
                dragging = true
                val dx = (event.x - downX).coerceAtLeast(0f)
                target.translationX = dx
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging || trackingHorizontal) {
                    val dx = target.translationX
                    if (dx >= dismissDistance) {
                        onSwipeDismiss?.invoke()
                    } else {
                        target.animate().translationX(0f).setDuration(160L).start()
                    }
                    dragging = false
                    trackingHorizontal = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
