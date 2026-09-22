package com.volumemanager.app.overlay

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Display + UI mapping: seek progress ↔ linear player gain.
 *
 * Below unity the bar is **equal-dB** (`MIN_DB..0`), not linear amplitude —
 * otherwise “1%” ≈ −25 dB and sounds like tens of percent. Boost zone stays
 * `0..MAX_DB`. Persist / apply paths still store linear gain.
 */
object DbVolumeMapper {
    const val MIN_DB = -40f
    const val UNITY_DB = 0f
    /** Digital boost ceiling above system/player unity. */
    const val MAX_DB = 6f

    val maxLinear: Float = 10f.pow(MAX_DB / 20f)

    /** Linear level that equals [MIN_DB] (`10^(MIN_DB/20)`). */
    private val minLinear = 10f.pow(MIN_DB / 20f)

    fun linearToDb(linear: Float): Float {
        if (linear <= 0f) return Float.NEGATIVE_INFINITY
        val clamped = linear.coerceIn(0f, maxLinear)
        if (clamped <= minLinear) return MIN_DB
        return (20f * log10(clamped)).coerceIn(MIN_DB, MAX_DB)
    }

    fun dbToLinear(db: Float): Float {
        if (db.isNaN()) return 0f
        if (db == Float.NEGATIVE_INFINITY || db < MIN_DB) return 0f
        if (db >= MAX_DB) return maxLinear
        return 10f.pow(db / 20f).coerceIn(0f, maxLinear)
    }

    /**
     * Seek progress `0..1` → apply volume.
     * `0` → mute; `(0, unityUi]` → `MIN_DB..0` dB; `(unityUi, 1]` → `0..MAX_DB`.
     */
    fun uiToLinear(ui: Float, unityUi: Float): Float {
        val u = ui.coerceIn(0f, 1f)
        if (u <= 0f) return 0f

        val unity = unityUi.coerceIn(0f, 1f)
        if (unity >= 0.999f || u <= unity) {
            val t = if (unity >= 0.999f || unity <= 0f) u else (u / unity)
            val tClamped = t.coerceIn(0f, 1f)
            if (tClamped <= 0f) return 0f
            val db = MIN_DB + tClamped * (UNITY_DB - MIN_DB)
            return dbToLinear(db)
        }

        val t = ((u - unity) / (1f - unity)).coerceIn(0f, 1f)
        return dbToLinear(t * MAX_DB)
    }

    fun linearToUi(linear: Float, unityUi: Float): Float {
        val v = linear.coerceIn(0f, maxLinear)
        if (v <= 0f) return 0f

        val unity = unityUi.coerceIn(0f, 1f)
        if (v <= 1f) {
            val db = linearToDb(v).coerceIn(MIN_DB, UNITY_DB)
            val t = ((db - MIN_DB) / (UNITY_DB - MIN_DB)).coerceIn(0f, 1f)
            return if (unity >= 0.999f) t else (t * unity).coerceIn(0f, 1f)
        }

        val db = linearToDb(v).coerceIn(UNITY_DB, MAX_DB)
        val t = (db / MAX_DB).coerceIn(0f, 1f)
        return (unity + t * (1f - unity)).coerceIn(0f, 1f)
    }

    fun formatDb(linear: Float): String {
        val db = linearToDb(linear)
        if (db == Float.NEGATIVE_INFINITY || linear <= 0f) return "−∞"
        val rounded = db.roundToInt()
        return when {
            rounded > 0 -> "+$rounded dB"
            rounded == 0 -> "0 dB"
            else -> "$rounded dB"
        }
    }

    private fun log10(v: Float): Float = ln(v) / LN_10

    private val LN_10 = ln(10.0).toFloat()
}
