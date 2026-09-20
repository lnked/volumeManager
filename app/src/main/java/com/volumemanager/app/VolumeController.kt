package com.volumemanager.app

/**
 * Flavor-specific privileged backend (Magisk/libsu or Sui/Shizuku).
 */
interface VolumeController {
    interface Listener {
        fun onAvailabilityChanged(available: Boolean)
        fun onServiceReady(ready: Boolean)
        fun onPlaybackChanged() {}
    }

    fun start()
    fun stop()
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    fun isAvailable(): Boolean

    /** Root granted / Shizuku running+permission — safe to call [bind]. */
    fun canAttemptBind(): Boolean

    fun bind()
    fun bindWithRetry()

    fun listActivePlaybacks(): List<PlaybackAppInfo>
    fun setPackageVolume(packageName: String, volume: Float)
    fun getStoredVolume(packageName: String): Float

    /** Overlay subtitle when privileged path is unavailable. */
    fun fallbackHintRes(): Int
}
