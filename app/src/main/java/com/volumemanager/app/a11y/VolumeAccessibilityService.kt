package com.volumemanager.app.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.volumemanager.app.VolumeManagerApp
import com.volumemanager.app.overlay.VolumeOverlayController

/**
 * Intercepts Volume Up/Down:
 * - first press → show floating overlay (no volume change on short tap)
 * - while overlay visible / key held → step focused slider continuously
 *
 * Also tracks the foreground app package so the overlay can focus that column.
 *
 * Note: on some OEMs (ASUS etc.) AudioService still briefly shows the system
 * VolumeDialog even when we return true from [onKeyEvent]. We dismiss it.
 */
class VolumeAccessibilityService : AccessibilityService() {
    private var overlay: VolumeOverlayController? = null
    @Volatile
    private var lastForegroundPackage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Uptime of last volume key we consumed — window for dismissing leaked VolumeDialog. */
    @Volatile
    private var lastVolumeKeyUptimeMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        val controller = VolumeManagerApp.instance.volumeController
        if (controller.canAttemptBind()) {
            controller.bind()
        }
        ensureOverlay()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (isSystemVolumePanelEvent(event) && shouldSuppressSystemVolumeUi()) {
            // VolumeDialog often appears above our overlay when the OEM still
            // dispatches the HW key to AudioService — collapse it.
            mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
        }

        val pkg = event.packageName?.toString() ?: return
        if (shouldIgnorePackage(pkg)) return
        lastForegroundPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            -> Unit
            else -> return false
        }

        // Camera apps use Volume− as shutter — do not steal the key.
        val fg = foregroundPackage()
        if (fg != null && CameraAppDetector.isCameraPackage(this, fg)) {
            return false
        }

        val volumeUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP

        val controller = VolumeManagerApp.instance.volumeController
        if (controller.canAttemptBind() && !controller.isAvailable()) {
            controller.bind()
        }

        lastVolumeKeyUptimeMs = SystemClock.uptimeMillis()
        val panel = ensureOverlay()

        // Always consume volume keys so PhoneWindowManager / AudioService ideally
        // do not show the system panel. (OEMs may still leak — see onAccessibilityEvent.)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) true
                else {
                    panel.onVolumeKeyDown(volumeUp)
                    true
                }
            }
            KeyEvent.ACTION_UP -> {
                panel.onVolumeKeyUp()
                true
            }
            else -> true
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        overlay?.release()
        overlay = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun ensureOverlay(): VolumeOverlayController {
        overlay?.let { return it }
        val created = VolumeOverlayController(this, VolumeManagerApp.instance.volumeController)
        overlay = created
        return created
    }

    private fun shouldSuppressSystemVolumeUi(): Boolean {
        if (overlay?.isShowing() == true) return true
        val age = SystemClock.uptimeMillis() - lastVolumeKeyUptimeMs
        return age in 0..VOLUME_DIALOG_SUPPRESS_MS
    }

    private fun isSystemVolumePanelEvent(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        if (pkg != "com.android.systemui" && pkg != "com.asus.systemui") return false
        val cls = event.className?.toString().orEmpty()
        if (cls.contains("VolumeDialog", ignoreCase = true) ||
            cls.contains("VolumePanel", ignoreCase = true) ||
            cls.contains("VolumeUI", ignoreCase = true)
        ) {
            return true
        }
        // Some builds only set content description / text.
        for (i in 0 until event.text.size) {
            val t = event.text[i]?.toString().orEmpty()
            if (t.contains("volume", ignoreCase = true) ||
                t.contains("громк", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    /** Best-effort foreground app (excludes self / SystemUI). */
    fun foregroundPackage(): String? {
        lastForegroundPackage?.let { if (!shouldIgnorePackage(it)) return it }
        rootInActiveWindow?.packageName?.toString()?.let { pkg ->
            if (!shouldIgnorePackage(pkg)) return pkg
        }
        try {
            for (window in windows.orEmpty()) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (!window.isActive && !window.isFocused) continue
                val pkg = window.root?.packageName?.toString() ?: continue
                if (!shouldIgnorePackage(pkg)) return pkg
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun shouldIgnorePackage(pkg: String): Boolean {
        return pkg == packageName ||
            pkg == "com.android.systemui" ||
            pkg == "com.asus.systemui" ||
            pkg == "com.android.permissioncontroller" ||
            pkg.startsWith("com.google.android.permissioncontroller")
    }

    companion object {
        private const val VOLUME_DIALOG_SUPPRESS_MS = 900L

        @Volatile
        var instance: VolumeAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null

        fun foregroundPackage(): String? = instance?.foregroundPackage()
    }
}
