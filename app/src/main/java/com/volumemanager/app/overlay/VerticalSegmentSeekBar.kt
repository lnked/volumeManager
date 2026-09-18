package com.volumemanager.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.volumemanager.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Vertical stack of discrete horizontal segments (Granular Volume style).
 * Progress `0f` = mute, `1f` = full; fill grows from the bottom.
 */
class VerticalSegmentSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    fun interface OnProgressChangeListener {
        fun onProgressChanged(seekBar: VerticalSegmentSeekBar, progress: Float, fromUser: Boolean)
    }

    var segmentCount: Int = DEFAULT_SEGMENTS
        set(value) {
            field = value.coerceAtLeast(2)
            invalidate()
        }

    private var progressInternal: Float = 0f

    var progress: Float
        get() = progressInternal
        set(value) = setProgress(value, fromUser = false)

    var listener: OnProgressChangeListener? = null

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.segment_active)
    }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.segment_inactive)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.segment_accent)
    }
    private val segmentRect = RectF()

    fun setProgress(value: Float, fromUser: Boolean) {
        val next = value.coerceIn(0f, 1f)
        if (abs(progressInternal - next) < 0.0001f) return
        progressInternal = next
        invalidate()
        listener?.onProgressChanged(this, progressInternal, fromUser)
    }

    fun stepBy(deltaSegments: Int) {
        val step = 1f / segmentCount
        setProgress(progressInternal + deltaSegments * step, fromUser = true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = resources.getDimensionPixelSize(R.dimen.volume_segment_bar_width)
        val desiredH = resources.getDimensionPixelSize(R.dimen.volume_segment_bar_height)
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gap = resources.getDimension(R.dimen.volume_segment_gap)
        val radius = resources.getDimension(R.dimen.volume_segment_radius)
        val totalGaps = gap * (segmentCount - 1)
        val segmentH = ((h - totalGaps) / segmentCount).coerceAtLeast(1f)
        val activeCount = (progressInternal * segmentCount).roundToInt().coerceIn(0, segmentCount)

        for (i in 0 until segmentCount) {
            val fromBottomIndex = segmentCount - 1 - i
            val top = i * (segmentH + gap)
            segmentRect.set(0f, top, w, top + segmentH)
            val paint = when {
                fromBottomIndex == activeCount - 1 && activeCount > 0 -> accentPaint
                fromBottomIndex < activeCount -> activePaint
                else -> inactivePaint
            }
            canvas.drawRoundRect(segmentRect, radius, radius, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateFromTouch(event.y)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(y: Float) {
        val h = height.toFloat().coerceAtLeast(1f)
        val raw = (1f - y / h).coerceIn(0f, 1f)
        val stepped = (raw * segmentCount).roundToInt().coerceIn(0, segmentCount) /
            segmentCount.toFloat()
        setProgress(stepped, fromUser = true)
    }

    companion object {
        const val DEFAULT_SEGMENTS = 24
    }
}
