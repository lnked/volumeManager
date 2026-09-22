package com.volumemanager.app

import android.media.AudioManager
import com.volumemanager.app.data.VolumePreferences

/**
 * Per-app [IPlayer.setVolume] is a multiplier on [AudioManager.STREAM_MUSIC].
 * If the stream is below max, package volume `1f` cannot get louder than that stream level.
 *
 * Lifts STREAM_MUSIC to max and scales stored multipliers by `oldStream/max` so perceived
 * loudness stays the same while sliders regain headroom up to true 0 dB.
 *
 * Skipped when any active/known game is below unity — those need stream scaling for SoundPool.
 */
object MediaStreamCeiling {
    /**
     * @return true if the stream was below max and volumes were rescaled.
     */
    fun ensureMaxWithRescale(
        audioManager: AudioManager,
        activePackages: Collection<String>,
        knownKeys: Collection<String>,
        getVolume: (String) -> Float,
        applyVolume: (String, Float) -> Unit,
        isGame: (String) -> Boolean = { false },
    ): Boolean {
        // Games/SoundPool follow STREAM_MUSIC; don't pin it to max while they are ducked.
        val keys = LinkedHashSet<String>()
        keys.addAll(knownKeys)
        keys.addAll(activePackages)
        for (key in keys) {
            if (key == VolumePreferences.STREAM_MUSIC_KEY) continue
            if (key == VolumePreferences.GAMES_GROUP_KEY || isGame(key)) {
                if (getVolume(key) < 0.999f) return false
            }
        }

        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (cur >= max) return false

        val ratio = cur.toFloat() / max.toFloat()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, /* flags */ 0)

        for (key in keys) {
            if (key == VolumePreferences.STREAM_MUSIC_KEY) continue
            val old = getVolume(key).coerceIn(0f, VolumePreferences.MAX_VOLUME)
            val scaled = (old * ratio).coerceIn(0f, VolumePreferences.MAX_VOLUME)
            applyVolume(key, scaled)
        }
        return true
    }
}
