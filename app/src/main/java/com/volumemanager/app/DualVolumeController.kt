package com.volumemanager.app

import com.volumemanager.app.data.BackendKind
import com.volumemanager.app.data.BackendPreferences
import com.volumemanager.app.root.RootVolumeController
import com.volumemanager.app.shizuku.ShizukuVolumeController
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single-APK facade: user picks Magisk/root or Sui/Shizuku; only the active backend runs.
 * On first launch with no saved choice, prefers root if granted, else Sui if Shizuku is up.
 */
class DualVolumeController(
    private val backendPrefs: BackendPreferences,
    val root: RootVolumeController,
    val sui: ShizukuVolumeController,
) : VolumeController {
    private val listeners = CopyOnWriteArrayList<VolumeController.Listener>()
    @Volatile
    private var kind: BackendKind = resolveInitialBackend()

    private val bridge = object : VolumeController.Listener {
        override fun onAvailabilityChanged(available: Boolean) {
            listeners.forEach { it.onAvailabilityChanged(active().isAvailable()) }
        }

        override fun onServiceReady(ready: Boolean) {
            listeners.forEach { it.onServiceReady(active().isAvailable()) }
        }

        override fun onPlaybackChanged() {
            listeners.forEach { it.onPlaybackChanged() }
        }
    }

    fun currentBackend(): BackendKind = kind

    fun selectBackend(next: BackendKind) {
        if (next == kind) {
            backendPrefs.setBackend(next)
            return
        }
        active().removeListener(bridge)
        active().stop()
        kind = next
        backendPrefs.setBackend(next)
        active().addListener(bridge)
        active().start()
        listeners.forEach {
            it.onAvailabilityChanged(active().isAvailable())
            it.onServiceReady(active().isAvailable())
        }
    }

    private fun resolveInitialBackend(): BackendKind {
        if (backendPrefs.hasExplicitBackend()) {
            return backendPrefs.getBackend()
        }
        val auto = when {
            root.isRootGranted() -> BackendKind.ROOT
            sui.isShizukuRunning() -> BackendKind.SUI
            else -> BackendKind.ROOT
        }
        backendPrefs.setBackend(auto)
        return auto
    }

    private fun active(): VolumeController = when (kind) {
        BackendKind.ROOT -> root
        BackendKind.SUI -> sui
    }

    override fun start() {
        active().addListener(bridge)
        active().start()
    }

    override fun stop() {
        active().removeListener(bridge)
        // Stop both so switching / process teardown never leaves a dangling binder.
        root.stop()
        sui.stop()
    }

    override fun addListener(listener: VolumeController.Listener) {
        listeners.add(listener)
        listener.onAvailabilityChanged(isAvailable())
        listener.onServiceReady(isAvailable())
    }

    override fun removeListener(listener: VolumeController.Listener) {
        listeners.remove(listener)
    }

    override fun isAvailable(): Boolean = active().isAvailable()

    override fun canAttemptBind(): Boolean = active().canAttemptBind()

    override fun bind() = active().bind()

    override fun bindWithRetry() = active().bindWithRetry()

    override fun listActivePlaybacks() = active().listActivePlaybacks()

    override fun setPackageVolume(packageName: String, volume: Float) =
        active().setPackageVolume(packageName, volume)

    override fun getStoredVolume(packageName: String): Float =
        active().getStoredVolume(packageName)

    override fun fallbackHintRes(): Int = active().fallbackHintRes()
}
