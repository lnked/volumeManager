package com.volumemanager.app

import android.os.Handler
import android.os.SystemClock

/**
 * Re-applies stored IPlayer volumes on a burst (and optional poll) so newly created
 * AudioTracks — video start, game engine recreate — get the intended gain before
 * they play at platform default (1.0).
 */
class StickyVolumeScheduler(
    private val handler: Handler,
    private val apply: () -> Unit,
) {
    private val burstToken = Any()
    private val periodicToken = Any()
    private var periodicActive = false

    private val periodic = object : Runnable {
        override fun run() {
            if (!periodicActive) return
            apply()
            handler.postAtTime(this, periodicToken, SystemClock.uptimeMillis() + PERIODIC_MS)
        }
    }

    fun scheduleBurst() {
        handler.removeCallbacksAndMessages(burstToken)
        val now = SystemClock.uptimeMillis()
        for (delay in BURST_DELAYS_MS) {
            handler.postAtTime({ apply() }, burstToken, now + delay)
        }
    }

    fun startPeriodic() {
        if (periodicActive) return
        periodicActive = true
        handler.postAtTime(periodic, periodicToken, SystemClock.uptimeMillis() + PERIODIC_MS)
    }

    fun stop() {
        periodicActive = false
        handler.removeCallbacksAndMessages(burstToken)
        handler.removeCallbacksAndMessages(periodicToken)
    }

    companion object {
        /** Covers prepare→play and late getPlayerProxy availability. */
        private val BURST_DELAYS_MS = longArrayOf(0L, 16L, 50L, 100L, 200L, 400L, 800L)
        /** SoundPool one-shots need sub-frame re-apply before game resets gain. */
        private const val PERIODIC_MS = 16L
    }
}
