package com.volumemanager.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.pow

/**
 * Persists per-package volume multipliers in `[0f, MAX_VOLUME]`,
 * plus pre-mute levels for unmute restore.
 *
 * Values above `1f` are digital boost (+dB) applied via IPlayer.setVolume when the
 * platform does not clamp.
 */
class VolumePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasVolume(packageName: String): Boolean = prefs.contains(key(packageName))

    fun getVolume(packageName: String, default: Float = DEFAULT_VOLUME): Float {
        if (!prefs.contains(key(packageName))) {
            return default.coerceIn(0f, MAX_VOLUME)
        }
        return prefs.getFloat(key(packageName), default).coerceIn(0f, MAX_VOLUME)
    }

    fun setVolume(packageName: String, volume: Float) {
        prefs.edit().putFloat(key(packageName), volume.coerceIn(0f, MAX_VOLUME)).apply()
    }

    fun getPreMuteVolume(packageName: String, default: Float = DEFAULT_VOLUME): Float {
        return prefs.getFloat(preMuteKey(packageName), default).coerceIn(0f, MAX_VOLUME)
    }

    fun setPreMuteVolume(packageName: String, volume: Float) {
        val clamped = volume.coerceIn(0f, MAX_VOLUME)
        if (clamped <= 0f) return
        prefs.edit().putFloat(preMuteKey(packageName), clamped).apply()
    }

    fun allVolumes(): Map<String, Float> {
        return prefs.all.mapNotNull { (k, v) ->
            if (k.startsWith(PREFIX) && v is Float) {
                k.removePrefix(PREFIX) to v.coerceIn(0f, MAX_VOLUME)
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
        /** Fallback only when resolver unavailable — prefer [DefaultVolumeResolver]. */
        const val DEFAULT_VOLUME = 1f
        /** +6 dB digital boost ceiling (`10^(6/20)`). */
        val MAX_VOLUME: Float = 10f.pow(6f / 20f)
        /** Synthetic key for global STREAM_MUSIC mute restore. */
        const val STREAM_MUSIC_KEY = "__stream_music__"
        /** Shared volume for all game apps (overlay games group). */
        const val GAMES_GROUP_KEY = "__games__"
    }
}
