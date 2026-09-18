package com.volumemanager.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists per-package volume multipliers in [0f, 1f],
 * plus pre-mute levels for unmute restore.
 */
class VolumePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getVolume(packageName: String, default: Float = DEFAULT_VOLUME): Float {
        return prefs.getFloat(key(packageName), default).coerceIn(0f, 1f)
    }

    fun setVolume(packageName: String, volume: Float) {
        prefs.edit().putFloat(key(packageName), volume.coerceIn(0f, 1f)).apply()
    }

    fun getPreMuteVolume(packageName: String, default: Float = DEFAULT_VOLUME): Float {
        return prefs.getFloat(preMuteKey(packageName), default).coerceIn(0f, 1f)
    }

    fun setPreMuteVolume(packageName: String, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        if (clamped <= 0f) return
        prefs.edit().putFloat(preMuteKey(packageName), clamped).apply()
    }

    fun allVolumes(): Map<String, Float> {
        return prefs.all.mapNotNull { (k, v) ->
            if (k.startsWith(PREFIX) && v is Float) {
                k.removePrefix(PREFIX) to v.coerceIn(0f, 1f)
            } else {
                null
            }
        }.toMap()
    }

    private fun key(packageName: String): String = PREFIX + packageName

    private fun preMuteKey(packageName: String): String = PRE_MUTE_PREFIX + packageName

    companion object {
        private const val PREFS_NAME = "per_app_volumes"
        private const val PREFIX = "vol_"
        private const val PRE_MUTE_PREFIX = "pre_mute_"
        const val DEFAULT_VOLUME = 1f
        /** Synthetic key for global STREAM_MUSIC mute restore. */
        const val STREAM_MUSIC_KEY = "__stream_music__"
    }
}
