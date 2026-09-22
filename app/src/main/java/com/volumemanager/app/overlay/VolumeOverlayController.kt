package com.volumemanager.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Outline
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.volumemanager.app.MediaStreamCeiling
import com.volumemanager.app.R
import com.volumemanager.app.VolumeController
import com.volumemanager.app.a11y.VolumeAccessibilityService
import com.volumemanager.app.data.AppSettingsPreferences
import com.volumemanager.app.data.OverlayPlaybackColumn
import com.volumemanager.app.data.PlaybackGrouping
import com.volumemanager.app.data.StoredVolumeLookup
import com.volumemanager.app.data.VolumePreferences

/**
 * Floating overlay: right-side dock with vertical per-app segmented sliders (dB UI).
 * Falls back to one STREAM_MUSIC column when privileged service is unavailable.
 *
 * HW Volume+/−: first press shows the panel; further presses / hold step the focused slider.
 * Double Volume− (within [DOUBLE_TAP_MUTE_MS]) mutes the focused column (active app or media).
 * Same slider also accepts drag and on-panel chevron buttons.
 *
 * Only STREAM_MUSIC / per-app player gain — never STREAM_RING / ringtone.
 */
class VolumeOverlayController(
    private val context: Context,
    private val volumeController: VolumeController,
) : VolumeController.Listener {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val preferences = VolumePreferences(context)
    private val appSettings = AppSettingsPreferences(context)
    private val inflater = LayoutInflater.from(context)
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: View? = null
    private var panelView: View? = null
    private val hideRunnable = Runnable { hideAnimated() }
    private var animatingOut = false
    private val seeks = mutableListOf<VerticalSegmentSeekBar>()
    private var focusedSeekIndex = 0
    private var focusedStorageKey: String? = null
    private var lastColumnKeys: List<String> = emptyList()
    private var columnStorageKeys: List<String> = emptyList()
    /** Non-null while a HW volume key is held; drives continuous step. */
    private var holdVolumeUp: Boolean? = null
    private var lastVolumeDownUptimeMs = 0L
    private val holdRunnable = object : Runnable {
        override fun run() {
            val up = holdVolumeUp ?: return
            if (!isShowing()) return
            stepFocused(if (up) 1 else -1, fromNewGesture = false)
            scheduleAutoHide()
            handler.postDelayed(this, HOLD_TICK_MS)
        }
    }

    init {
        volumeController.addListener(this)
    }

    /**
     * First tap (overlay hidden): show panel only.
     * While overlay visible: step once, then keep stepping if key stays down.
     * Double Volume−: mute focused app / STREAM_MUSIC column.
     * Hold after show: after [HOLD_START_MS] starts continuous step.
     */
    fun onVolumeKeyDown(volumeUp: Boolean): Boolean {
        holdVolumeUp = volumeUp
        handler.removeCallbacks(holdRunnable)

        if (!volumeUp) {
            val now = SystemClock.uptimeMillis()
            if (lastVolumeDownUptimeMs > 0L &&
                now - lastVolumeDownUptimeMs <= DOUBLE_TAP_MUTE_MS
            ) {
                lastVolumeDownUptimeMs = 0L
                stopHold()
                if (!isShowing()) show()
                muteFocused()
                scheduleAutoHide()
                return true
            }
            lastVolumeDownUptimeMs = now
        } else {
            lastVolumeDownUptimeMs = 0L
        }

        if (!isShowing()) {
            show()
            handler.postDelayed(holdRunnable, HOLD_START_MS)
            return true
        }
        stepFocused(if (volumeUp) 1 else -1, fromNewGesture = true)
        scheduleAutoHide()
        handler.postDelayed(holdRunnable, HOLD_START_MS)
        return true
    }

    fun onVolumeKeyUp(): Boolean {
        stopHold()
        return true
    }

    private fun stopHold() {
        holdVolumeUp = null
        handler.removeCallbacks(holdRunnable)
    }

    fun show() {
        if (rootView != null) {
            refreshContent(force = true)
            scheduleAutoHide()
            return
        }
        animatingOut = false
        val view = inflater.inflate(R.layout.overlay_volume, null)
        rootView = view
        panelView = view.findViewById(R.id.overlayPanel)
        (view as? SwipeDismissFrameLayout)?.onSwipeDismiss = { hideAnimated() }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { hideAnimated() }
        view.findViewById<HorizontalScrollView>(R.id.sliderScroll).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> handler.removeCallbacks(hideRunnable)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> scheduleAutoHide()
            }
            false
        }

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
            refreshContent(force = true)
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
            seeks.clear()
            android.util.Log.e(TAG, "addView failed", t)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        stopHold()
        val view = rootView ?: return
        rootView = null
        panelView = null
        seeks.clear()
        focusedSeekIndex = 0
        focusedStorageKey = null
        lastColumnKeys = emptyList()
        columnStorageKeys = emptyList()
        animatingOut = false
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
    }

    fun release() {
        hide()
        volumeController.removeListener(this)
    }

    override fun onAvailabilityChanged(available: Boolean) {
        if (isShowing()) {
            handler.post { refreshContent(force = true) }
        }
    }

    override fun onServiceReady(ready: Boolean) {
        if (isShowing()) {
            handler.post { refreshContent(force = true) }
        }
    }

    override fun onPlaybackChanged() {
        if (!isShowing()) return
        handler.post {
            refreshContent(force = false)
            scheduleAutoHide()
        }
    }

    private fun stepFocused(deltaSegments: Int, fromNewGesture: Boolean = true) {
        if (seeks.isEmpty()) return
        val idx = focusedSeekIndex.coerceIn(0, seeks.lastIndex)
        seeks[idx].stepBy(deltaSegments, fromNewGesture = fromNewGesture)
    }

    /** Mute focused column (active app / games group / STREAM_MUSIC). Saves pre-mute for unmute. */
    private fun muteFocused() {
        if (seeks.isEmpty()) {
            refreshContent(force = true)
        }
        if (seeks.isEmpty()) return
        val idx = focusedSeekIndex.coerceIn(0, seeks.lastIndex)
        val seek = seeks[idx]
        val key = focusedStorageKey
            ?: columnStorageKeys.getOrNull(idx)
            ?: return
        val linear = if (seek.boostCapacity > 0) {
            DbVolumeMapper.uiToLinear(seek.progress, seek.unityUiFraction)
        } else {
            seek.progress.coerceIn(0f, 1f)
        }
        if (linear <= 0f) return
        preferences.setPreMuteVolume(key, linear)
        if (seek.boostUnlocked) {
            seek.lockBoostZone(0f)
        }
        seek.setProgress(0f, fromUser = true)
    }

    private fun focusSeek(seek: VerticalSegmentSeekBar) {
        val idx = seeks.indexOf(seek)
        if (idx >= 0) {
            focusedSeekIndex = idx
            focusedStorageKey = columnStorageKeys.getOrNull(idx)
        }
    }

    private fun applyDefaultFocus(storageKeys: List<String>) {
        if (storageKeys.isEmpty()) {
            focusedSeekIndex = 0
            focusedStorageKey = null
            return
        }
        val preferredPkg = VolumeAccessibilityService.foregroundPackage()
        val preferredKey = when {
            preferredPkg != null && storageKeys.contains(preferredPkg) -> preferredPkg
            preferredPkg != null &&
                storageKeys.contains(VolumePreferences.GAMES_GROUP_KEY) &&
                PlaybackGrouping.isGame(context.packageManager, preferredPkg) ->
                VolumePreferences.GAMES_GROUP_KEY
            focusedStorageKey != null && storageKeys.contains(focusedStorageKey) ->
                focusedStorageKey
            else -> storageKeys.first()
        }
        focusedStorageKey = preferredKey
        focusedSeekIndex = storageKeys.indexOf(preferredKey).coerceAtLeast(0)
        scrollFocusedIntoView()
    }

    private fun scrollFocusedIntoView() {
        val view = rootView ?: return
        val scroll = view.findViewById<HorizontalScrollView>(R.id.sliderScroll)
        val container = view.findViewById<LinearLayout>(R.id.sliderContainer)
        val child = container.getChildAt(focusedSeekIndex) ?: return
        scroll.post {
            val target = (child.left - (scroll.width - child.width) / 2).coerceAtLeast(0)
            scroll.smoothScrollTo(target, 0)
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
        handler.postDelayed(hideRunnable, appSettings.getAutoHideMs())
    }

    @SuppressLint("SetTextI18n")
    private fun refreshContent(force: Boolean) {
        val view = rootView ?: return
        val subtitle = view.findViewById<TextView>(R.id.overlaySubtitle)
        val container = view.findViewById<LinearLayout>(R.id.sliderContainer)

        val gap = context.resources.getDimensionPixelSize(R.dimen.overlay_column_gap)
        val ready = volumeController.isAvailable()
        val privilegesPending = !ready && volumeController.canAttemptBind()
        val preferredPkg = VolumeAccessibilityService.foregroundPackage()
        val nextKeys: List<String>
        val columns: List<OverlayPlaybackColumn>
        var streamLifted = false
        if (ready) {
            val apps = volumeController.listActivePlaybacks()
            if (apps.isEmpty()) {
                // Global STREAM_MUSIC column — never lift stream (that fights the slider).
                nextKeys = listOf(VolumePreferences.STREAM_MUSIC_KEY)
                columns = emptyList()
            } else {
                // Per-app IPlayer.setVolume is capped by STREAM_MUSIC — lift + rescale once.
                streamLifted = liftMediaStreamIfNeeded(apps.map { it.packageName })
                columns = PlaybackGrouping.columns(
                    apps = apps,
                    pm = context.packageManager,
                    gamesLabel = context.getString(R.string.overlay_games_group),
                    preferredPackage = preferredPkg,
                )
                syncGamesGroupToPackages(columns)
                nextKeys = columns.map { it.storageKey }
            }
        } else if (privilegesPending) {
            // Root/Sui granted but service not bound yet — never fall back to STREAM_MUSIC.
            nextKeys = listOf("__pending__")
            columns = emptyList()
        } else {
            nextKeys = listOf("__fallback__")
            columns = emptyList()
        }

        if (!force && !streamLifted && nextKeys == lastColumnKeys) {
            // Columns unchanged; still re-focus active app if user switched apps.
            if (ready &&
                columnStorageKeys.isNotEmpty() &&
                columnStorageKeys != listOf(VolumePreferences.STREAM_MUSIC_KEY)
            ) {
                applyDefaultFocus(columnStorageKeys)
            }
            return
        }
        lastColumnKeys = nextKeys

        container.removeAllViews()
        seeks.clear()
        columnStorageKeys = emptyList()

        when {
            ready && columns.isNotEmpty() -> {
                subtitle.visibility = View.GONE
                columns.forEachIndexed { index, column ->
                    val viewCol = addPlaybackColumn(container, column)
                    if (index > 0) {
                        (viewCol.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                    }
                }
                columnStorageKeys = columns.map { it.storageKey }
                applyDefaultFocus(columnStorageKeys)
            }
            ready -> {
                // Idle: no active playbacks — global media is intentional.
                subtitle.visibility = View.GONE
                addGlobalMediaColumn(container)
                columnStorageKeys = listOf(VolumePreferences.STREAM_MUSIC_KEY)
                focusedSeekIndex = 0
                focusedStorageKey = VolumePreferences.STREAM_MUSIC_KEY
            }
            privilegesPending -> {
                subtitle.setText(volumeController.fallbackHintRes())
                subtitle.visibility = View.VISIBLE
                focusedSeekIndex = 0
                focusedStorageKey = null
            }
            else -> {
                subtitle.setText(volumeController.fallbackHintRes())
                subtitle.visibility = View.VISIBLE
                addGlobalMediaColumn(container)
                columnStorageKeys = listOf(VolumePreferences.STREAM_MUSIC_KEY)
                focusedSeekIndex = 0
                focusedStorageKey = VolumePreferences.STREAM_MUSIC_KEY
            }
        }
    }

    private fun liftMediaStreamIfNeeded(
        activePackages: Collection<String> = volumeController.listActivePlaybacks().map { it.packageName },
    ): Boolean {
        return MediaStreamCeiling.ensureMaxWithRescale(
            audioManager = audioManager,
            activePackages = activePackages,
            knownKeys = preferences.allVolumes().keys,
            // Unknown packages: treat player as 1f so lift scales to system ratio once.
            getVolume = { pkg ->
                StoredVolumeLookup.getOrUnity(preferences, context.packageManager, pkg)
            },
            applyVolume = { pkg, vol -> volumeController.setPackageVolume(pkg, vol) },
            isGame = { pkg -> PlaybackGrouping.isGame(context.packageManager, pkg) },
        )
    }

    private fun syncGamesGroupToPackages(columns: List<OverlayPlaybackColumn>) {
        val games = columns.firstOrNull { it.isGamesGroup } ?: return
        val volume = volumeController.getStoredVolume(VolumePreferences.GAMES_GROUP_KEY)
        for (pkg in games.packageNames) {
            if (volumeController.getStoredVolume(pkg) != volume) {
                volumeController.setPackageVolume(pkg, volume)
            }
        }
    }

    private fun addPlaybackColumn(container: LinearLayout, column: OverlayPlaybackColumn): View {
        val view = inflater.inflate(R.layout.item_vertical_volume, container, false)
        val icon = view.findViewById<ImageView>(R.id.appIcon)
        val dbText = view.findViewById<TextView>(R.id.volumeDb)
        val seek = view.findViewById<VerticalSegmentSeekBar>(R.id.volumeSeek)
        val btnUp = view.findViewById<ImageButton>(R.id.btnVolumeUp)
        val btnDown = view.findViewById<ImageButton>(R.id.btnVolumeDown)
        val btnMute = view.findViewById<ImageButton>(R.id.btnMute)

        if (column.isGamesGroup) {
            icon.setImageResource(R.drawable.ic_games)
        } else {
            try {
                icon.setImageDrawable(context.packageManager.getApplicationIcon(column.iconPackage))
            } catch (_: PackageManager.NameNotFoundException) {
                icon.setImageResource(R.drawable.ic_launcher_foreground)
            }
        }
        clipAppIcon(icon)
        icon.contentDescription = column.label

        val stored = volumeController.getStoredVolume(column.storageKey)
            .coerceIn(0f, VolumePreferences.MAX_VOLUME)
        bindSeek(
            seek = seek,
            dbText = dbText,
            btnUp = btnUp,
            btnDown = btnDown,
            btnMute = btnMute,
            muteKey = column.storageKey,
            initialLinear = stored,
            allowBoost = true,
            onChange = { linear ->
                setColumnVolume(column, linear)
            },
        )
        container.addView(view)
        return view
    }

    private fun setColumnVolume(column: OverlayPlaybackColumn, linear: Float) {
        if (column.isGamesGroup) {
            volumeController.setPackageVolume(VolumePreferences.GAMES_GROUP_KEY, linear)
            for (pkg in column.packageNames) {
                volumeController.setPackageVolume(pkg, linear)
            }
        } else {
            volumeController.setPackageVolume(column.packageNames.first(), linear)
        }
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
        clipAppIcon(icon)
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
            initialLinear = initial,
            allowBoost = false,
            onChange = { linear ->
                val level = (linear.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            },
        )
        container.addView(column)
    }

    private fun clipAppIcon(icon: ImageView) {
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width
                val h = view.height
                if (w <= 0 || h <= 0) return
                val r = w.coerceAtMost(h) * 0.22f
                outline.setRoundRect(0, 0, w, h, r)
            }
        }
        icon.clipToOutline = true
        if (icon.width == 0 || icon.height == 0) {
            icon.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    if (v.width > 0 && v.height > 0) {
                        v.removeOnLayoutChangeListener(this)
                        v.invalidateOutline()
                    }
                }
            })
        } else {
            icon.invalidateOutline()
        }
    }

    private fun bindSeek(
        seek: VerticalSegmentSeekBar,
        dbText: TextView,
        btnUp: ImageButton,
        btnDown: ImageButton,
        btnMute: ImageButton,
        muteKey: String,
        initialLinear: Float,
        allowBoost: Boolean,
        onChange: (Float) -> Unit,
    ) {
        seek.boostCapacity =
            if (allowBoost) VerticalSegmentSeekBar.DEFAULT_BOOST_SEGMENTS else 0
        // Boost segments stay hidden until a dedicated up-press at 100%.
        if (allowBoost && initialLinear > 1f + 0.0001f) {
            seek.unlockBoostZone()
        } else {
            seek.lockBoostZone()
        }

        fun toLinear(ui: Float): Float =
            if (allowBoost) DbVolumeMapper.uiToLinear(ui, seek.unityUiFraction)
            else ui.coerceIn(0f, 1f)

        fun toUi(linear: Float): Float =
            if (allowBoost) DbVolumeMapper.linearToUi(linear, seek.unityUiFraction)
            else linear.coerceIn(0f, 1f)

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
            val clamped = linear.coerceIn(0f, if (allowBoost) VolumePreferences.MAX_VOLUME else 1f)
            if (allowBoost && clamped > 1f) {
                if (!seek.boostUnlocked) seek.unlockBoostZone()
            } else if (allowBoost && seek.boostUnlocked && clamped <= 1f) {
                seek.lockBoostZone()
            }
            seek.setProgress(toUi(clamped), fromUser = false)
            updateLabel(clamped)
            updateMuteIcon(clamped)
            if (fromUser) {
                onChange(clamped)
            }
        }

        seek.listener = VerticalSegmentSeekBar.OnProgressChangeListener { _, progress, fromUser ->
            val linear = toLinear(progress)
            updateLabel(linear)
            updateMuteIcon(linear)
            if (fromUser) {
                if (linear > 0f) {
                    preferences.setPreMuteVolume(muteKey, linear)
                }
                onChange(linear)
                scheduleAutoHide()
            }
        }
        val initial = initialLinear.coerceIn(0f, if (allowBoost) VolumePreferences.MAX_VOLUME else 1f)
        seek.setProgress(toUi(initial), fromUser = false)
        updateLabel(initial)
        updateMuteIcon(initial)
        if (initial > 0f) {
            preferences.setPreMuteVolume(muteKey, initial)
        }

        seeks.add(seek)

        btnUp.setOnClickListener {
            focusSeek(seek)
            seek.stepBy(1, fromNewGesture = true)
            scheduleAutoHide()
        }
        btnDown.setOnClickListener {
            focusSeek(seek)
            seek.stepBy(-1, fromNewGesture = true)
            scheduleAutoHide()
        }
        btnMute.setOnClickListener {
            focusSeek(seek)
            val current = toLinear(seek.progress)
            if (current <= 0f) {
                val restored = preferences.getPreMuteVolume(muteKey, VolumePreferences.DEFAULT_VOLUME)
                    .coerceIn(MUTE_RESTORE_MIN, if (allowBoost) VolumePreferences.MAX_VOLUME else 1f)
                applyVolume(restored, fromUser = true)
            } else {
                preferences.setPreMuteVolume(muteKey, current)
                applyVolume(0f, fromUser = true)
            }
            scheduleAutoHide()
        }

        seek.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    focusSeek(seek)
                    handler.removeCallbacks(hideRunnable)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> scheduleAutoHide()
            }
            false
        }
    }

    companion object {
        private const val TAG = "VolumeOverlay"
        private const val ANIM_MS = 220L
        /** Delay before continuous step while key is held (ViewConfiguration-like). */
        private const val HOLD_START_MS = 400L
        private const val HOLD_TICK_MS = 50L
        /** Two Volume− downs within this window → mute focused column. */
        private const val DOUBLE_TAP_MUTE_MS = 380L
        /** Avoid restoring a literal 0 after unmute when pre-mute missing/zero. */
        private const val MUTE_RESTORE_MIN = 0.05f
    }
}
