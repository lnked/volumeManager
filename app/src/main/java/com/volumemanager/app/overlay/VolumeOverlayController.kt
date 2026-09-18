package com.volumemanager.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.volumemanager.app.PlaybackAppInfo
import com.volumemanager.app.R
import com.volumemanager.app.VolumeController
import com.volumemanager.app.data.VolumePreferences

/**
 * Floating overlay: right-side dock with vertical per-app segmented sliders (dB UI).
 * Falls back to one STREAM_MUSIC column when privileged service is unavailable.
 */
class VolumeOverlayController(
    private val context: Context,
    private val volumeController: VolumeController,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val preferences = VolumePreferences(context)
    private val inflater = LayoutInflater.from(context)
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: View? = null
    private var panelView: View? = null
    private val hideRunnable = Runnable { hideAnimated() }
    private var animatingOut = false

    fun toggleOrRefresh() {
        if (rootView != null) {
            refreshContent()
            scheduleAutoHide()
        } else {
            show()
        }
    }

    fun show() {
        if (rootView != null) {
            refreshContent()
            scheduleAutoHide()
            return
        }
        animatingOut = false
        val view = inflater.inflate(R.layout.overlay_volume, null)
        rootView = view
        panelView = view.findViewById(R.id.overlayPanel)
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { hideAnimated() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        try {
            windowManager.addView(view, params)
            refreshContent()
            view.post {
                val panel = panelView ?: return@post
                panel.translationX = panel.width.toFloat() +
                    context.resources.getDimension(R.dimen.overlay_panel_margin)
                panel.animate()
                    .translationX(0f)
                    .setDuration(ANIM_MS)
                    .start()
            }
            scheduleAutoHide()
        } catch (t: Throwable) {
            rootView = null
            panelView = null
            android.util.Log.e(TAG, "addView failed", t)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        val view = rootView ?: return
        rootView = null
        panelView = null
        animatingOut = false
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun hideAnimated() {
        if (animatingOut) return
        val panel = panelView
        val view = rootView
        if (panel == null || view == null) {
            hide()
            return
        }
        animatingOut = true
        handler.removeCallbacks(hideRunnable)
        panel.animate()
            .translationX(panel.width.toFloat() + context.resources.getDimension(R.dimen.overlay_panel_margin))
            .setDuration(ANIM_MS)
            .withEndAction { hide() }
            .start()
    }

    fun isShowing(): Boolean = rootView != null

    private fun scheduleAutoHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshContent() {
        val view = rootView ?: return
        val subtitle = view.findViewById<TextView>(R.id.overlaySubtitle)
        val moreCount = view.findViewById<TextView>(R.id.overlayMoreCount)
        val container = view.findViewById<LinearLayout>(R.id.sliderContainer)
        container.removeAllViews()
        moreCount.visibility = View.GONE

        val gap = context.resources.getDimensionPixelSize(R.dimen.overlay_column_gap)
        val ready = volumeController.isAvailable()
        if (ready) {
            val apps = volumeController.listActivePlaybacks()
            if (apps.isEmpty()) {
                // Idle: global STREAM_MUSIC only, no empty-state subtitle.
                subtitle.visibility = View.GONE
                addGlobalMediaColumn(container)
            } else {
                subtitle.visibility = View.GONE
                val shown = apps.take(MAX_COLUMNS)
                shown.forEachIndexed { index, app ->
                    val column = addPerAppColumn(container, app)
                    if (index > 0) {
                        (column.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                    }
                }
                val extra = apps.size - shown.size
                if (extra > 0) {
                    moreCount.text = context.getString(R.string.overlay_more_apps, extra)
                    moreCount.visibility = View.VISIBLE
                }
            }
        } else {
            subtitle.setText(volumeController.fallbackHintRes())
            subtitle.visibility = View.VISIBLE
            addGlobalMediaColumn(container)
        }
    }

    private fun addPerAppColumn(container: LinearLayout, app: PlaybackAppInfo): View {
        val column = inflater.inflate(R.layout.item_vertical_volume, container, false)
        val icon = column.findViewById<ImageView>(R.id.appIcon)
        val dbText = column.findViewById<TextView>(R.id.volumeDb)
        val seek = column.findViewById<VerticalSegmentSeekBar>(R.id.volumeSeek)
        val btnUp = column.findViewById<ImageButton>(R.id.btnVolumeUp)
        val btnDown = column.findViewById<ImageButton>(R.id.btnVolumeDown)
        val btnMute = column.findViewById<ImageButton>(R.id.btnMute)

        try {
            icon.setImageDrawable(context.packageManager.getApplicationIcon(app.packageName))
        } catch (_: PackageManager.NameNotFoundException) {
            icon.setImageResource(R.drawable.ic_launcher_foreground)
        }

        val stored = volumeController.getStoredVolume(app.packageName).coerceIn(0f, 1f)
        bindSeek(
            seek = seek,
            dbText = dbText,
            btnUp = btnUp,
            btnDown = btnDown,
            btnMute = btnMute,
            muteKey = app.packageName,
            initial = stored,
            onChange = { linear ->
                volumeController.setPackageVolume(app.packageName, linear)
            },
        )
        container.addView(column)
        return column
    }

    private fun addGlobalMediaColumn(container: LinearLayout) {
        val column = inflater.inflate(R.layout.item_vertical_volume, container, false)
        val icon = column.findViewById<ImageView>(R.id.appIcon)
        val dbText = column.findViewById<TextView>(R.id.volumeDb)
        val seek = column.findViewById<VerticalSegmentSeekBar>(R.id.volumeSeek)
        val btnUp = column.findViewById<ImageButton>(R.id.btnVolumeUp)
        val btnDown = column.findViewById<ImageButton>(R.id.btnVolumeDown)
        val btnMute = column.findViewById<ImageButton>(R.id.btnMute)

        icon.setImageResource(R.drawable.ic_launcher_foreground)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val initial = current.toFloat() / max

        bindSeek(
            seek = seek,
            dbText = dbText,
            btnUp = btnUp,
            btnDown = btnDown,
            btnMute = btnMute,
            muteKey = VolumePreferences.STREAM_MUSIC_KEY,
            initial = initial,
            onChange = { linear ->
                val level = (linear * max).toInt().coerceIn(0, max)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            },
        )
        container.addView(column)
    }

    private fun bindSeek(
        seek: VerticalSegmentSeekBar,
        dbText: TextView,
        btnUp: ImageButton,
        btnDown: ImageButton,
        btnMute: ImageButton,
        muteKey: String,
        initial: Float,
        onChange: (Float) -> Unit,
    ) {
        fun updateLabel(linear: Float) {
            dbText.text = DbVolumeMapper.formatDb(linear)
        }

        fun updateMuteIcon(linear: Float) {
            val muted = linear <= 0f
            btnMute.setImageResource(
                if (muted) R.drawable.ic_volume_mute else R.drawable.ic_volume_unmute,
            )
            btnMute.contentDescription = context.getString(
                if (muted) R.string.overlay_unmute else R.string.overlay_mute,
            )
        }

        fun applyVolume(linear: Float, fromUser: Boolean) {
            val clamped = linear.coerceIn(0f, 1f)
            seek.setProgress(clamped, fromUser = false)
            updateLabel(clamped)
            updateMuteIcon(clamped)
            if (fromUser) {
                onChange(clamped)
            }
        }

        seek.listener = VerticalSegmentSeekBar.OnProgressChangeListener { _, progress, fromUser ->
            updateLabel(progress)
            updateMuteIcon(progress)
            if (fromUser) {
                if (progress > 0f) {
                    preferences.setPreMuteVolume(muteKey, progress)
                }
                onChange(progress)
                scheduleAutoHide()
            }
        }
        seek.setProgress(initial, fromUser = false)
        updateLabel(initial)
        updateMuteIcon(initial)
        if (initial > 0f) {
            preferences.setPreMuteVolume(muteKey, initial)
        }

        btnUp.setOnClickListener {
            seek.stepBy(1)
            scheduleAutoHide()
        }
        btnDown.setOnClickListener {
            seek.stepBy(-1)
            scheduleAutoHide()
        }
        btnMute.setOnClickListener {
            val current = seek.progress.coerceIn(0f, 1f)
            if (current <= 0f) {
                val restored = preferences.getPreMuteVolume(muteKey, VolumePreferences.DEFAULT_VOLUME)
                    .coerceIn(MUTE_RESTORE_MIN, 1f)
                applyVolume(restored, fromUser = true)
            } else {
                preferences.setPreMuteVolume(muteKey, current)
                applyVolume(0f, fromUser = true)
            }
            scheduleAutoHide()
        }

        seek.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> handler.removeCallbacks(hideRunnable)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> scheduleAutoHide()
            }
            false
        }
    }

    companion object {
        private const val TAG = "VolumeOverlay"
        private const val AUTO_HIDE_MS = 1500L
        private const val ANIM_MS = 220L
        private const val MAX_COLUMNS = 3
        /** Avoid restoring a literal 0 after unmute when pre-mute missing/zero. */
        private const val MUTE_RESTORE_MIN = 0.05f
    }
}
