package com.volumemanager.app

import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * SoundPool (and many game engines) ignore IPlayer/VolumeShaper and follow
 * [AudioManager.STREAM_MUSIC]. When games need a volume below unity, scale the
 * stream index to match — otherwise SFX stay at 100% while music is ducked.
 *
 * Mapping: app gain `0..1` → stream index `0..max` (100% = full system volume).
 * When no game/SoundPool is actively ducking, restore stream to max so
 * IPlayer-based apps (video etc.) are not stuck under a leftover 1/max ceiling.
 */
object GameStreamScaler {
    /**
     * @param target01 linear gain in `0..1` for active game/SoundPool packages,
     * or null when nothing needs stream ducking (restores STREAM_MUSIC to max).
     */
    fun sync(audioManager: AudioManager, target01: Float?) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val idx = when {
            target01 == null || target01 >= 0.999f -> max
            target01 <= 0f -> 0
            else -> {
                // Map 0..1 onto 1..max so the quietest non-zero slider still has an index.
                (target01 * max).roundToInt().coerceIn(1, max)
            }
        }
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != idx) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, /* flags */ 0)
        }
    }
}
