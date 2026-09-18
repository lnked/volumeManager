package com.volumemanager.app.overlay

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Display mapping only: linear volume `0f..1f` ↔ dB for UI.
 * Persist / apply paths stay on the linear float.
 */
object DbVolumeMapper {
    const val MIN_DB = -40f
    const val MAX_DB = 0f

    /** Linear level that equals [MIN_DB] (`10^(MIN_DB/20)`). */
    private val minLinear = 10f.pow(MIN_DB / 20f)

    fun linearToDb(linear: Float): Float {
        if (linear <= 0f) return Float.NEGATIVE_INFINITY
        val clamped = linear.coerceIn(0f, 1f)
        if (clamped <= minLinear) return MIN_DB
        return (20f * log10(clamped)).coerceIn(MIN_DB, MAX_DB)
    }

    fun dbToLinear(db: Float): Float {
        if (db.isNaN()) return 0f
        if (db == Float.NEGATIVE_INFINITY || db <= MIN_DB) return 0f
        if (db >= MAX_DB) return 1f
        return 10f.pow(db / 20f).coerceIn(0f, 1f)
    }

    fun formatDb(linear: Float): String {
        val db = linearToDb(linear)
        if (db == Float.NEGATIVE_INFINITY || linear <= 0f) return "−∞"
        val rounded = db.roundToInt()
        return if (rounded >= 0) "0 dB" else "$rounded dB"
    }

    private fun log10(v: Float): Float = ln(v) / LN_10

    private val LN_10 = ln(10.0).toFloat()
}
