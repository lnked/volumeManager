package com.volumemanager.app

import android.media.AudioPlaybackConfiguration
import android.util.Log
import com.volumemanager.app.data.VolumePreferences

/**
 * Applies per-player gain via VolumeShaper + IPlayer.setVolume.
 *
 * - MediaPlayer: VolumeShaper sticks (replace curve each apply); setVolume(1) for (0,1]
 * - SoundPool: shaper usually ignored → always setVolume(real gain); sticky re-apply
 */
object PlayerVolumeApplier {
    private const val TAG = "PlayerVolApply"
    private const val GAIN_SHAPER_ID = 0x564D3031 // "VM01"
    /** [AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL] */
    private const val PLAYER_TYPE_SOUNDPOOL = 3

    @Volatile
    private var shaperReady = false
    private var configClass: Class<*>? = null
    private var builderClass: Class<*>? = null
    private var opClass: Class<*>? = null
    private var opBuilderClass: Class<*>? = null
    private var playBase: Any? = null
    private var applyShaperMethod: java.lang.reflect.Method? = null
    private var getPlayerTypeMethod: java.lang.reflect.Method? = null
    private var clockFlag: Int = 1

    fun apply(config: AudioPlaybackConfiguration, volume: Float) {
        val player = playerProxy(config) ?: return
        val v = volume.coerceIn(0f, VolumePreferences.MAX_VOLUME)
        val soundPool = isSoundPool(config)
        val shaperOk = if (soundPool) {
            false
        } else {
            applyGainShaper(player, v.coerceIn(0f, 1f))
        }

        val multiplier = when {
            v <= 0f -> 0f
            v > 1f -> v
            shaperOk -> 1f
            else -> v.coerceIn(0f, 1f)
        }
        try {
            val setVolume = player.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
            setVolume.invoke(player, multiplier)
        } catch (t: Throwable) {
            Log.w(TAG, "setVolume failed", t)
        }
    }

    /** Exposed for [GameStreamScaler] decisions in the privileged services. */
    fun isSoundPoolType(config: AudioPlaybackConfiguration): Boolean = isSoundPool(config)

    private fun isSoundPool(config: AudioPlaybackConfiguration): Boolean {
        return try {
            val m = getPlayerTypeMethod
                ?: AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerType").also {
                    it.isAccessible = true
                    getPlayerTypeMethod = it
                }
            (m.invoke(config) as Int) == PLAYER_TYPE_SOUNDPOOL
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyGainShaper(player: Any, gain01: Float): Boolean {
        if (ensureShaper(player) != true) return false
        val cfg = buildGainConfig(gain01) ?: return false
        val createOk = invokeShaper(player, cfg, buildCreateOp())
        val replaceOk = invokeShaper(player, cfg, buildReplaceOp())
        return createOk || replaceOk
    }

    private fun invokeShaper(player: Any, cfg: Any, op: Any?): Boolean {
        if (op == null) return false
        return try {
            applyShaperMethod!!.invoke(player, cfg, op)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "applyVolumeShaper failed", t)
            false
        }
    }

    private fun buildGainConfig(gain01: Float): Any? {
        val bClass = builderClass ?: return null
        return try {
            val builder = bClass.getDeclaredConstructor().newInstance()
            bClass.getMethod("setId", Int::class.javaPrimitiveType)
                .invoke(builder, GAIN_SHAPER_ID)
            val g = gain01.coerceIn(0f, 1f)
            bClass.getMethod("setCurve", FloatArray::class.java, FloatArray::class.java)
                .invoke(builder, floatArrayOf(0f, 1f), floatArrayOf(g, g))
            try {
                val interp = configClass!!.getField("INTERPOLATOR_TYPE_LINEAR").getInt(null)
                bClass.getMethod("setInterpolatorType", Int::class.javaPrimitiveType)
                    .invoke(builder, interp)
            } catch (_: Throwable) {
            }
            try {
                bClass.getMethod("setOptionFlags", Int::class.javaPrimitiveType)
                    .invoke(builder, clockFlag)
            } catch (_: Throwable) {
            }
            bClass.getMethod("setDuration", Long::class.javaPrimitiveType)
                .invoke(builder, 20L)
            bClass.getMethod("build").invoke(builder)
        } catch (t: Throwable) {
            Log.w(TAG, "buildGainConfig failed", t)
            null
        }
    }

    private fun buildCreateOp(): Any? {
        val obClass = opBuilderClass ?: return null
        val base = playBase ?: return null
        return try {
            val opBuilder = obClass.getConstructor(opClass).newInstance(base)
            obClass.getMethod("createIfNeeded").invoke(opBuilder)
            obClass.getMethod("build").invoke(opBuilder)
        } catch (t: Throwable) {
            Log.w(TAG, "buildCreateOp failed", t)
            null
        }
    }

    private fun buildReplaceOp(): Any? {
        val obClass = opBuilderClass ?: return null
        val base = playBase ?: return null
        return try {
            val opBuilder = obClass.getConstructor(opClass).newInstance(base)
            obClass.getMethod(
                "replace",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(opBuilder, GAIN_SHAPER_ID, java.lang.Boolean.FALSE)
            obClass.getMethod("build").invoke(opBuilder)
        } catch (t: Throwable) {
            Log.w(TAG, "buildReplaceOp failed", t)
            null
        }
    }

    private fun ensureShaper(player: Any): Boolean? {
        if (shaperReady) return true
        synchronized(this) {
            if (shaperReady) return true
            return try {
                configClass = Class.forName("android.media.VolumeShaper\$Configuration")
                builderClass = Class.forName("android.media.VolumeShaper\$Configuration\$Builder")
                opClass = Class.forName("android.media.VolumeShaper\$Operation")
                opBuilderClass = Class.forName("android.media.VolumeShaper\$Operation\$Builder")
                clockFlag = try {
                    configClass!!.getField("OPTION_FLAG_CLOCK_TIME").getInt(null)
                } catch (_: Throwable) {
                    1
                }
                playBase = opClass!!.getField("PLAY").get(null)
                applyShaperMethod = player.javaClass.getMethod(
                    "applyVolumeShaper",
                    configClass,
                    opClass,
                )
                shaperReady = true
                true
            } catch (t: Throwable) {
                Log.w(TAG, "VolumeShaper reflection init failed", t)
                null
            }
        }
    }

    private fun playerProxy(config: AudioPlaybackConfiguration): Any? {
        return try {
            val getProxy = AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerProxy")
            getProxy.isAccessible = true
            getProxy.invoke(config)
        } catch (t: Throwable) {
            Log.w(TAG, "getPlayerProxy failed", t)
            null
        }
    }
}
