package com.volumemanager.app

import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * SoundPool (and many game engines) ignore IPlayer/VolumeShaper and follow
 * [AudioManager.STREAM_MUSIC]. When games need a volume below unity, scale the
 * stream index to match — otherwise SFX stay at 100% while music is ducked.
 */
object GameStreamScaler {
    /**
     * @param target01 linear gain in `0..1` for active game/SoundPool packages,
     * or null to leave the stream unchanged.
     */
    fun sync(audioManager: AudioManager, target01: Float?) {
        if (target01 == null) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val t = target01.coerceIn(0f, 1f)
        val idx = when {
            t <= 0f -> 0
            t >= 0.999f -> max
            else -> {
                // Map onto 1..max so the quietest non-zero slider still has an index.
                val stepped = (t * max).roundToInt()
                stepped.coerceIn(1, max)
            }
        }
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != idx) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, /* flags */ 0)
        }
    }
}
