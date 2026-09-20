package com.volumemanager.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Overlay + default-volume settings (auto-hide, category defaults).
 */
class AppSettingsPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAutoHideMs(): Long {
        val seconds = prefs.getFloat(KEY_AUTO_HIDE_SEC, DEFAULT_AUTO_HIDE_SEC)
            .coerceIn(MIN_AUTO_HIDE_SEC, MAX_AUTO_HIDE_SEC)
        return (seconds * 1000f).toLong().coerceAtLeast(100L)
    }

    fun getAutoHideSeconds(): Float =
        prefs.getFloat(KEY_AUTO_HIDE_SEC, DEFAULT_AUTO_HIDE_SEC)
            .coerceIn(MIN_AUTO_HIDE_SEC, MAX_AUTO_HIDE_SEC)

    fun setAutoHideSeconds(seconds: Float) {
        val stepped = (seconds.coerceIn(MIN_AUTO_HIDE_SEC, MAX_AUTO_HIDE_SEC) * 10f)
            .toInt() / 10f
        prefs.edit().putFloat(KEY_AUTO_HIDE_SEC, stepped).apply()
    }

    fun isGamesAsSystem(): Boolean = prefs.getBoolean(KEY_GAMES_AS_SYSTEM, true)

    fun setGamesAsSystem(value: Boolean) {
        prefs.edit().putBoolean(KEY_GAMES_AS_SYSTEM, value).apply()
    }

    /** Linear 0..1 when [isGamesAsSystem] is false. */
    fun getGamesDefaultVolume(): Float =
        prefs.getFloat(KEY_GAMES_VOLUME, 0f).coerceIn(0f, 1f)

    fun setGamesDefaultVolume(volume: Float) {
        prefs.edit().putFloat(KEY_GAMES_VOLUME, volume.coerceIn(0f, 1f)).apply()
    }

    fun isOtherAsSystem(): Boolean = prefs.getBoolean(KEY_OTHER_AS_SYSTEM, true)

    fun setOtherAsSystem(value: Boolean) {
        prefs.edit().putBoolean(KEY_OTHER_AS_SYSTEM, value).apply()
    }

    fun getOtherDefaultVolume(): Float =
        prefs.getFloat(KEY_OTHER_VOLUME, 1f).coerceIn(0f, 1f)

    fun setOtherDefaultVolume(volume: Float) {
        prefs.edit().putFloat(KEY_OTHER_VOLUME, volume.coerceIn(0f, 1f)).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_AUTO_HIDE_SEC = "auto_hide_sec"
        private const val KEY_GAMES_AS_SYSTEM = "games_as_system"
        private const val KEY_GAMES_VOLUME = "games_volume"
        private const val KEY_OTHER_AS_SYSTEM = "other_as_system"
        private const val KEY_OTHER_VOLUME = "other_volume"

        const val MIN_AUTO_HIDE_SEC = 1.0f
        const val MAX_AUTO_HIDE_SEC = 10.0f
        const val DEFAULT_AUTO_HIDE_SEC = 2.8f
        /** SeekBar progress steps: 1.0..10.0 at 0.1 → 0..90 */
        const val AUTO_HIDE_STEPS = 90
    }
}
