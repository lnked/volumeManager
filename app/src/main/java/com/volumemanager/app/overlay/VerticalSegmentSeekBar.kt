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
 * Vertical stack of discrete segments. Fill grows from the bottom.
 *
 * Boost (>0 dB) segments stay **hidden** until the user is at 100% (0 dB) and
 * does a **new** Volume+/chevron press. Hold repeats that only reached unity do
 * not reveal boost. Dropping below unity hides boost again.
 */
class VerticalSegmentSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    fun interface OnProgressChangeListener {
        fun onProgressChanged(seekBar: VerticalSegmentSeekBar, progress: Float, fromUser: Boolean)
    }

    /** Normal (≤0 dB) segments — always visible. */
    var normalSegmentCount: Int = DEFAULT_NORMAL_SEGMENTS
        set(value) {
            field = value.coerceAtLeast(2)
            invalidate()
        }

    /** Potential boost segments; drawn only while [boostUnlocked]. `0` = no boost (STREAM_MUSIC). */
    var boostCapacity: Int = DEFAULT_BOOST_SEGMENTS
        set(value) {
            field = value.coerceAtLeast(0)
            if (field == 0 && boostUnlocked) {
                boostUnlocked = false
            }
            invalidate()
        }

    /** True after a dedicated up-press at 100% — orange boost segments visible. */
    var boostUnlocked: Boolean = false
        private set

    val segmentCount: Int
        get() = normalSegmentCount + if (boostUnlocked) boostCapacity else 0

    /** UI progress at unity. `1f` while boost hidden. */
    val unityUiFraction: Float
        get() {
            val total = segmentCount
            if (total <= 0) return 1f
            if (!boostUnlocked || boostCapacity <= 0) return 1f
            return normalSegmentCount.toFloat() / total.toFloat()
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
    private val boostActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.segment_boost_active)
    }
    private val boostInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.segment_boost_inactive)
    }
    private val boostAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.segment_boost_accent)
    }
    private val segmentRect = RectF()

    /** Reveal boost after a dedicated up-press at 100%. Volume stays at unity (0 dB). */
    fun unlockBoostZone() {
        if (boostCapacity <= 0 || boostUnlocked) return
        boostUnlocked = true
        progressInternal = unityUiFraction
        invalidate()
    }

    /** Hide boost; [linear01] is apply-volume in `0..1`. */
    fun lockBoostZone(linear01: Float) {
        boostUnlocked = false
        progressInternal = linear01.coerceIn(0f, 1f)
        invalidate()
    }

    fun setProgress(value: Float, fromUser: Boolean) {
        setProgress(value, fromUser = fromUser, allowBoostEnter = false)
    }

    private fun setProgress(value: Float, fromUser: Boolean, allowBoostEnter: Boolean) {
        var next = value.coerceIn(0f, 1f)
        if (fromUser && boostUnlocked && boostCapacity > 0) {
            val unity = unityUiFraction
            val inBoost = progressInternal > unity + UNITY_EPS
            if (next > unity && !inBoost && !allowBoostEnter) {
                next = unity
            }
        }
        if (abs(progressInternal - next) < 0.0001f) return
        progressInternal = next
        invalidate()
        listener?.onProgressChanged(this, progressInternal, fromUser)
    }

    /**
     * @param fromNewGesture true for discrete Volume+/chevron taps; false for hold repeats.
     * Only a new gesture at unity reveals the boost zone.
     */
    fun stepBy(deltaSegments: Int, fromNewGesture: Boolean = true) {
        if (deltaSegments == 0) return
        val total = segmentCount
        val currentSeg = (progressInternal * total).roundToInt().coerceIn(0, total)
        val unitySeg = if (boostUnlocked) normalSegmentCount else total

        if (deltaSegments > 0 && boostCapacity > 0 && !boostUnlocked) {
            if (currentSeg >= unitySeg) {
                if (fromNewGesture) unlockBoostZone()
                return
            }
            val targetSeg = (currentSeg + deltaSegments).coerceAtMost(unitySeg)
            setProgress(targetSeg.toFloat() / total.toFloat(), fromUser = true)
            return
        }

        if (deltaSegments > 0 && boostUnlocked) {
            val targetSeg = if (currentSeg >= unitySeg) {
                (currentSeg + deltaSegments).coerceAtMost(total)
            } else {
                (currentSeg + deltaSegments).coerceAtMost(unitySeg)
            }
            setProgress(
                targetSeg.toFloat() / total.toFloat(),
                fromUser = true,
                allowBoostEnter = currentSeg >= unitySeg,
            )
            return
        }

        val targetSeg = (currentSeg + deltaSegments).coerceIn(0, total)
        if (boostUnlocked && targetSeg < normalSegmentCount) {
            val linear01 = targetSeg.toFloat() / normalSegmentCount.toFloat()
            lockBoostZone(linear01)
            listener?.onProgressChanged(this, progressInternal, true)
            return
        }
        setProgress(targetSeg.toFloat() / total.toFloat(), fromUser = true)
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

        val count = segmentCount
        if (count <= 0) return

        val gap = resources.getDimension(R.dimen.volume_segment_gap)
        val radius = resources.getDimension(R.dimen.volume_segment_radius)
        val totalGaps = gap * (count - 1)
        val segmentH = ((h - totalGaps) / count).coerceAtLeast(1f)
        val activeCount = (progressInternal * count).roundToInt().coerceIn(0, count)
        val unityCount = if (boostUnlocked) normalSegmentCount else count

        for (i in 0 until count) {
            val fromBottomIndex = count - 1 - i
            val top = i * (segmentH + gap)
            segmentRect.set(0f, top, w, top + segmentH)
            val isBoost = boostUnlocked && fromBottomIndex >= unityCount
            val paint = when {
                fromBottomIndex == activeCount - 1 && activeCount > 0 ->
                    if (isBoost) boostAccentPaint else accentPaint
                fromBottomIndex < activeCount ->
                    if (isBoost) boostActivePaint else activePaint
                else ->
                    if (isBoost) boostInactivePaint else inactivePaint
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
        val count = segmentCount
        val stepped = (raw * count).roundToInt().coerceIn(0, count)

        if (boostUnlocked && stepped < normalSegmentCount) {
            val linear01 = stepped.toFloat() / normalSegmentCount.toFloat()
            lockBoostZone(linear01)
            listener?.onProgressChanged(this, progressInternal, true)
            return
        }

        val next = stepped.toFloat() / count.toFloat()
        if (boostUnlocked) {
            setProgress(next, fromUser = true, allowBoostEnter = stepped > normalSegmentCount)
        } else {
            // Drag never unlocks boost — only stepBy(..., fromNewGesture=true) at unity.
            setProgress(next.coerceAtMost(1f), fromUser = true)
        }
    }

    companion object {
        const val DEFAULT_NORMAL_SEGMENTS = 19
        const val DEFAULT_BOOST_SEGMENTS = 5
        private const val UNITY_EPS = 0.0001f
    }
}
