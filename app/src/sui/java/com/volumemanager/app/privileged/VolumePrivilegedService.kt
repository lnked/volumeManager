package com.volumemanager.app.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.volumemanager.app.IVolumePrivilegedService
import com.volumemanager.app.PlaybackAppInfo
import java.lang.reflect.Method

/**
 * Runs under Shizuku/Sui (shell) identity. Enumerates active playbacks and applies
 * per-UID volume via hidden IPlayer.setVolume.
 *
 * Shizuku v13+ prefers the Context constructor; older builds use the no-arg one.
 */
class VolumePrivilegedService : IVolumePrivilegedService.Stub {
    private val context: Context
    private val audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var storedVolumes: MutableMap<String, Float> = mutableMapOf()
    private var watcherRegistered = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            applyStoredToConfigs(configs.orEmpty())
        }
    }

    /** Primary path on Shizuku 13+. */
    constructor(context: Context) {
        this.context = context.applicationContext ?: context
        audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.i(TAG, "constructed with Context (${this.context.javaClass.simpleName})")
    }

    /**
     * Fallback for older Shizuku. Must not throw when [currentApplication] is null —
     * use [ActivityThread.systemMain].getSystemContext() instead.
     */
    constructor() : this(resolveBootstrapContext())

    override fun destroy() {
        Log.i(TAG, "destroy()")
        try {
            unregisterPlaybackWatcher()
        } catch (t: Throwable) {
            Log.w(TAG, "destroy cleanup failed", t)
        }
        System.exit(0)
    }

    override fun listActivePlaybacks(): MutableList<PlaybackAppInfo> {
        val configs = audioManager.activePlaybackConfigurations
        val byPackage = linkedMapOf<String, MutableList<AudioPlaybackConfiguration>>()

        for (config in configs) {
            val pkg = packageForConfig(config) ?: continue
            // Keep muted / stored packages even when isActive becomes false after setVolume(0).
            if (!isPlayingLike(config) && !storedVolumes.containsKey(pkg)) continue
            byPackage.getOrPut(pkg) { mutableListOf() }.add(config)
        }

        val pm = context.packageManager
        return byPackage.map { (pkg, list) ->
            val uid = clientUid(list.first())
            val label = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
            PlaybackAppInfo(
                packageName = pkg,
                uid = uid,
                label = label,
                playerCount = list.size,
            )
        }.toMutableList()
    }

    override fun setPackageVolume(packageName: String, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        storedVolumes[packageName] = clamped
        val configs = audioManager.activePlaybackConfigurations
        for (config in configs) {
            val pkg = packageForConfig(config) ?: continue
            if (pkg == packageName) {
                setPlayerVolume(config, clamped)
            }
        }
    }

    override fun applyStoredVolumes(stored: Bundle?) {
        storedVolumes.clear()
        if (stored != null) {
            for (key in stored.keySet()) {
                storedVolumes[key] = stored.getFloat(key, 1f).coerceIn(0f, 1f)
            }
        }
        applyStoredToConfigs(audioManager.activePlaybackConfigurations)
    }

    override fun registerPlaybackWatcher() {
        if (watcherRegistered) return
        audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        watcherRegistered = true
        applyStoredToConfigs(audioManager.activePlaybackConfigurations)
    }

    override fun unregisterPlaybackWatcher() {
        if (!watcherRegistered) return
        try {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterPlaybackWatcher failed", t)
        }
        watcherRegistered = false
    }

    private fun applyStoredToConfigs(configs: List<AudioPlaybackConfiguration>) {
        if (storedVolumes.isEmpty()) return
        for (config in configs) {
            val pkg = packageForConfig(config) ?: continue
            val vol = storedVolumes[pkg] ?: continue
            setPlayerVolume(config, vol)
        }
    }

    private fun packageForConfig(config: AudioPlaybackConfiguration): String? {
        val uid = clientUid(config)
        if (uid <= 0) return null
        val packages = context.packageManager.getPackagesForUid(uid)
        return packages?.firstOrNull()
    }

    private fun clientUid(config: AudioPlaybackConfiguration): Int {
        return try {
            val m: Method = AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid")
            m.isAccessible = true
            m.invoke(config) as Int
        } catch (t: Throwable) {
            Log.w(TAG, "getClientUid failed", t)
            -1
        }
    }

    private fun isPlayingLike(config: AudioPlaybackConfiguration): Boolean {
        return try {
            val m = AudioPlaybackConfiguration::class.java.getDeclaredMethod("isActive")
            m.isAccessible = true
            m.invoke(config) as Boolean
        } catch (_: Throwable) {
            true
        }
    }

    private fun setPlayerVolume(config: AudioPlaybackConfiguration, volume: Float) {
        try {
            val getProxy = AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerProxy")
            getProxy.isAccessible = true
            val player = getProxy.invoke(config) ?: return
            val setVolume = player.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
            setVolume.invoke(player, volume)
        } catch (t: Throwable) {
            Log.w(TAG, "setPlayerVolume failed for config", t)
        }
    }

    companion object {
        private const val TAG = "VolPrivService"

        private fun resolveBootstrapContext(): Context {
            currentApplication()?.let {
                Log.i(TAG, "bootstrap via currentApplication()")
                return it
            }
            systemMainContext()?.let {
                Log.i(TAG, "bootstrap via ActivityThread.systemMain()")
                return it
            }
            Log.e(TAG, "bootstrap context unavailable — last-chance systemMain")
            return systemMainContextOrThrow()
        }

        private fun currentApplication(): Context? {
            return try {
                val at = Class.forName("android.app.ActivityThread")
                val m = at.getDeclaredMethod("currentApplication")
                m.invoke(null) as? Context
            } catch (t: Throwable) {
                Log.w(TAG, "currentApplication() failed", t)
                null
            }
        }

        private fun systemMainContext(): Context? {
            return try {
                val at = Class.forName("android.app.ActivityThread")
                val thread = at.getDeclaredMethod("systemMain").invoke(null) ?: return null
                val getCtx = thread.javaClass.getMethod("getSystemContext")
                getCtx.invoke(thread) as? Context
            } catch (t: Throwable) {
                Log.w(TAG, "systemMain().getSystemContext() failed", t)
                null
            }
        }

        private fun systemMainContextOrThrow(): Context {
            return systemMainContext()
                ?: throw IllegalStateException("No Context for VolumePrivilegedService")
        }
    }
}
