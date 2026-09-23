package com.volumemanager.app

import android.media.AudioManager
import com.volumemanager.app.data.VolumePreferences

/**
 * Per-app [IPlayer.setVolume] is a multiplier on [AudioManager.STREAM_MUSIC].
 * If the stream is below max, package volume `1f` cannot get louder than that stream level.
 *
 * Lifts STREAM_MUSIC to max. When no games are involved, also scales stored multipliers
 * by `oldStream/max` so perceived loudness stays the same while sliders regain headroom.
 *
 * Skipped while an *active* game is below unity — those need stream ducking for SoundPool.
 * When games are known but not actively ducked, lift only (no rescale): GameStreamScaler
 * may have left the stream at 1/max; rescaling would permanently crush all prefs.
 */
object MediaStreamCeiling {
    /**
     * @return true if the stream was below max and was lifted (and optionally volumes rescaled).
     */
    fun ensureMaxWithRescale(
        audioManager: AudioManager,
        activePackages: Collection<String>,
        knownKeys: Collection<String>,
        getVolume: (String) -> Float,
        applyVolume: (String, Float) -> Unit,
        isGame: (String) -> Boolean = { false },
    ): Boolean {
        val gamesGroup = getVolume(VolumePreferences.GAMES_GROUP_KEY)
        val activeGamePlaying = activePackages.any { isGame(it) }
        if (activeGamePlaying && gamesGroup < 0.999f) return false
        for (pkg in activePackages) {
            if (pkg == VolumePreferences.STREAM_MUSIC_KEY) continue
            if (isGame(pkg) && getVolume(pkg) < 0.999f) return false
        }

        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (cur >= max) return false

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, /* flags */ 0)

        val gameAware = knownKeys.any {
            it == VolumePreferences.GAMES_GROUP_KEY || isGame(it)
        } || activeGamePlaying
        if (gameAware) return true

        val ratio = cur.toFloat() / max.toFloat()
        val keys = LinkedHashSet<String>()
        keys.addAll(knownKeys)
        keys.addAll(activePackages)
        for (key in keys) {
            if (key == VolumePreferences.STREAM_MUSIC_KEY) continue
            val old = getVolume(key).coerceIn(0f, VolumePreferences.MAX_VOLUME)
            val scaled = (old * ratio).coerceIn(0f, VolumePreferences.MAX_VOLUME)
            applyVolume(key, scaled)
        }
        return true
    }
}
