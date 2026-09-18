package com.volumemanager.app.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.volumemanager.app.VolumeManagerApp
import com.volumemanager.app.overlay.VolumeOverlayController

/**
 * Intercepts Volume Up/Down and shows the floating per-app volume overlay.
 */
class VolumeAccessibilityService : AccessibilityService() {
    private var overlay: VolumeOverlayController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }

        val controller = VolumeManagerApp.instance.volumeController
        if (controller.canAttemptBind()) {
            controller.bind()
        }
        overlay = VolumeOverlayController(this, controller)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val isVolume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolume) return false

        val controller = VolumeManagerApp.instance.volumeController
        if (controller.canAttemptBind() && !controller.isAvailable()) {
            controller.bind()
        }
        overlay?.toggleOrRefresh()
        return true
    }

    override fun onDestroy() {
        overlay?.hide()
        overlay = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: VolumeAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
