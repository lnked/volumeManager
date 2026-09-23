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
import com.volumemanager.app.AppOpsPlayAudioMute
import com.volumemanager.app.GameStreamScaler
import com.volumemanager.app.IPlaybackChangeListener
import com.volumemanager.app.IVolumePrivilegedService
import com.volumemanager.app.PlaybackAppInfo
import com.volumemanager.app.PlayerVolumeApplier
import com.volumemanager.app.StickyVolumeScheduler
import com.volumemanager.app.data.PlaybackGrouping
import com.volumemanager.app.data.VolumePreferences
import java.lang.reflect.Method

/**
 * Root process via libsu. Enumerates active playbacks and applies per-UID volume
 * through IPlayer + VolumeShaper; volume 0 also tries AppOps PLAY_AUDIO.
 */
class VolumeRootService : RootService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var storedVolumes: MutableMap<String, Float> = mutableMapOf()
    private val usageGamePackages = mutableSetOf<String>()
    private var watcherRegistered = false
    @Volatile
    private var playbackListener: IPlaybackChangeListener? = null

    private val sticky = StickyVolumeScheduler(mainHandler) {
        val am = audioManager ?: return@StickyVolumeScheduler
        applyStoredToConfigs(am.activePlaybackConfigurations)
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            applyStoredToConfigs(configs.orEmpty())
            sticky.scheduleBurst()
            notifyPlaybackChanged()
        }
    }

    private val binder = object : IVolumePrivilegedService.Stub() {
        override fun destroy() {
            Log.i(TAG, "destroy()")
            try {
                setPlaybackChangeListener(null)
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
                noteGameUsage(pkg, config)
                val kept = volumeForPackage(pkg) != null
                if (!isPlayingLike(config) && !kept) continue
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
            val clamped = volume.coerceIn(0f, VolumePreferences.MAX_VOLUME)
            storedVolumes[packageName] = clamped
            val isGamesGroup = packageName == VolumePreferences.GAMES_GROUP_KEY
            for (config in am.activePlaybackConfigurations) {
                val pkg = packageForConfig(config) ?: continue
                noteGameUsage(pkg, config)
                val match = if (isGamesGroup) {
                    isGamePackage(pkg)
                } else {
                    pkg == packageName
                }
                if (!match) continue
                PlayerVolumeApplier.apply(config, clamped)
                applyVolumeToPackage(pkg, clamped)
            }
            if (!isGamesGroup) {
                applyVolumeToPackage(packageName, clamped)
            } else {
                for (pkg in storedVolumes.keys) {
                    if (pkg.startsWith("__")) continue
                    if (isGamePackage(pkg)) {
                        applyVolumeToPackage(pkg, clamped)
                    }
                }
            }
            syncGameStream(am.activePlaybackConfigurations)
            sticky.scheduleBurst()
        }

        override fun applyStoredVolumes(stored: Bundle?) {
            storedVolumes.clear()
            if (stored != null) {
                for (key in stored.keySet()) {
                    storedVolumes[key] = stored.getFloat(key, 1f).coerceIn(0f, VolumePreferences.MAX_VOLUME)
                }
            }
            val am = audioManager ?: return
            applyStoredToConfigs(am.activePlaybackConfigurations)
            syncHardMutesFromStored()
            sticky.scheduleBurst()
        }

        override fun registerPlaybackWatcher() {
            val am = audioManager ?: return
            if (watcherRegistered) return
            am.registerAudioPlaybackCallback(playbackCallback, mainHandler)
            watcherRegistered = true
            applyStoredToConfigs(am.activePlaybackConfigurations)
            syncHardMutesFromStored()
            sticky.scheduleBurst()
            sticky.startPeriodic()
        }

        override fun unregisterPlaybackWatcher() {
            if (!watcherRegistered) return
            sticky.stop()
            try {
                audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterPlaybackWatcher failed", t)
            }
            watcherRegistered = false
        }

        override fun setPlaybackChangeListener(listener: IPlaybackChangeListener?) {
            playbackListener = listener
        }
    }

    private fun notifyPlaybackChanged() {
        try {
            playbackListener?.onPlaybackChanged()
        } catch (t: Throwable) {
            Log.w(TAG, "playback listener failed", t)
            playbackListener = null
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
            if (storedVolumes.isNotEmpty()) {
                for (config in configs) {
                    val pkg = packageForConfig(config) ?: continue
                    noteGameUsage(pkg, config)
                    val vol = volumeForPackage(pkg) ?: continue
                    PlayerVolumeApplier.apply(config, vol)
                    applyVolumeToPackage(pkg, vol)
                }
            }
            syncGameStream(configs)
        }

        /** Duck STREAM_MUSIC only for actively playing game/SoundPool; else restore max. */
        private fun syncGameStream(configs: List<AudioPlaybackConfiguration>) {
            val am = audioManager ?: return
            var streamTarget: Float? = null
            for (config in configs) {
                if (!isPlayingLike(config)) continue
                val pkg = packageForConfig(config) ?: continue
                if (!isGamePackage(pkg) && !PlayerVolumeApplier.isSoundPoolType(config)) continue
                val vol = volumeForPackage(pkg) ?: continue
                val g = vol.coerceIn(0f, 1f)
                streamTarget = streamTarget?.let { minOf(it, g) } ?: g
            }
            GameStreamScaler.sync(am, streamTarget)
        }

    private fun syncHardMutesFromStored() {
        if (storedVolumes.isEmpty()) return
        val am = audioManager ?: return
        val gamesGroup = storedVolumes[VolumePreferences.GAMES_GROUP_KEY]
        for ((pkg, vol) in storedVolumes) {
            if (pkg.startsWith("__")) continue
            val effective = if (gamesGroup != null && isGamePackage(pkg)) {
                gamesGroup
            } else {
                vol
            }
            applyVolumeToPackage(pkg, effective)
        }
        if (gamesGroup != null) {
            for (config in am.activePlaybackConfigurations) {
                val pkg = packageForConfig(config) ?: continue
                noteGameUsage(pkg, config)
                if (isGamePackage(pkg)) {
                    applyVolumeToPackage(pkg, gamesGroup)
                }
            }
        }
    }

    private fun applyVolumeToPackage(pkg: String, volume: Float) {
        AppOpsPlayAudioMute.setMuted(this, pkg, volume <= 0f)
    }

    private fun noteGameUsage(pkg: String, config: AudioPlaybackConfiguration) {
        try {
            if (PlaybackGrouping.isGameUsage(config.audioAttributes.usage)) {
                usageGamePackages.add(pkg)
            }
        } catch (_: Throwable) {
        }
    }

    private fun isGamePackage(pkg: String): Boolean =
        usageGamePackages.contains(pkg) ||
            PlaybackGrouping.isGame(packageManager, pkg)

    private fun volumeForPackage(pkg: String): Float? {
        storedVolumes[pkg]?.let { return it }
        val gamesGroup = storedVolumes[VolumePreferences.GAMES_GROUP_KEY] ?: return null
        return if (isGamePackage(pkg)) gamesGroup else null
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

    companion object {
        private const val TAG = "VolRootService"
    }
}
