package com.volumemanager.app.root

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import com.volumemanager.app.IVolumePrivilegedService
import com.volumemanager.app.PlaybackAppInfo
import java.lang.reflect.Method

/**
 * Root process via libsu. Enumerates active playbacks and applies per-UID volume
 * through hidden IPlayer.setVolume.
 */
class VolumeRootService : RootService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var storedVolumes: MutableMap<String, Float> = mutableMapOf()
    private var watcherRegistered = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            applyStoredToConfigs(configs.orEmpty())
        }
    }

    private val binder = object : IVolumePrivilegedService.Stub() {
        override fun destroy() {
            Log.i(TAG, "destroy()")
            try {
                unregisterPlaybackWatcher()
            } catch (t: Throwable) {
                Log.w(TAG, "destroy cleanup failed", t)
            }
            stopSelf()
        }

        override fun listActivePlaybacks(): MutableList<PlaybackAppInfo> {
            val am = audioManager ?: return mutableListOf()
            val configs = am.activePlaybackConfigurations
            val byPackage = linkedMapOf<String, MutableList<AudioPlaybackConfiguration>>()

            for (config in configs) {
                val pkg = packageForConfig(config) ?: continue
                // Keep muted / stored packages even when isActive becomes false after setVolume(0).
                if (!isPlayingLike(config) && !storedVolumes.containsKey(pkg)) continue
                byPackage.getOrPut(pkg) { mutableListOf() }.add(config)
            }

            val pm = packageManager
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
            val am = audioManager ?: return
            val clamped = volume.coerceIn(0f, 1f)
            storedVolumes[packageName] = clamped
            for (config in am.activePlaybackConfigurations) {
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
            val am = audioManager ?: return
            applyStoredToConfigs(am.activePlaybackConfigurations)
        }

        override fun registerPlaybackWatcher() {
            val am = audioManager ?: return
            if (watcherRegistered) return
            am.registerAudioPlaybackCallback(playbackCallback, mainHandler)
            watcherRegistered = true
            applyStoredToConfigs(am.activePlaybackConfigurations)
        }

        override fun unregisterPlaybackWatcher() {
            if (!watcherRegistered) return
            try {
                audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterPlaybackWatcher failed", t)
            }
            watcherRegistered = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        Log.i(TAG, "onCreate uid=${android.os.Process.myUid()}")
    }

    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "onBind")
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.i(TAG, "onUnbind")
        return true
    }

    override fun onDestroy() {
        try {
            binder.unregisterPlaybackWatcher()
        } catch (_: Throwable) {
        }
        Log.i(TAG, "onDestroy")
        super.onDestroy()
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
        val packages = packageManager.getPackagesForUid(uid)
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
        private const val TAG = "VolRootService"
    }
}
