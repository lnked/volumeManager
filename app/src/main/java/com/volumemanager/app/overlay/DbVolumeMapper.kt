package com.volumemanager.app.overlay

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Display + UI mapping: linear volume `0f..MAX_LINEAR` ↔ dB / seek progress.
 * Persist / apply paths use linear (may exceed `1f` in the boost zone).
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
        if (db == Float.NEGATIVE_INFINITY || db <= MIN_DB) return 0f
        if (db >= MAX_DB) return maxLinear
        return 10f.pow(db / 20f).coerceIn(0f, maxLinear)
    }

    /**
     * Seek progress `0..1` → apply volume.
     * `[0, unityUi]` → `0..1`; `(unityUi, 1]` → `1..maxLinear` (+dB boost).
     */
    fun uiToLinear(ui: Float, unityUi: Float): Float {
        val u = ui.coerceIn(0f, 1f)
        if (unityUi >= 0.999f) return u
        if (u <= unityUi) {
            return if (unityUi <= 0f) 0f else (u / unityUi).coerceIn(0f, 1f)
        }
        val t = ((u - unityUi) / (1f - unityUi)).coerceIn(0f, 1f)
        return dbToLinear(t * MAX_DB)
    }

    fun linearToUi(linear: Float, unityUi: Float): Float {
        val v = linear.coerceIn(0f, maxLinear)
        if (unityUi >= 0.999f) return v.coerceIn(0f, 1f)
        if (v <= 1f) return (v * unityUi).coerceIn(0f, 1f)
        val db = linearToDb(v).coerceIn(UNITY_DB, MAX_DB)
        val t = db / MAX_DB
        return (unityUi + t * (1f - unityUi)).coerceIn(0f, 1f)
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
