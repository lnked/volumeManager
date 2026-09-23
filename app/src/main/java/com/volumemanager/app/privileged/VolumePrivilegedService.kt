package com.volumemanager.app.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * Runs under Shizuku/Sui (shell) identity. Enumerates active playbacks and applies
 * per-UID volume via IPlayer + VolumeShaper; volume 0 also tries AppOps PLAY_AUDIO.
 *
 * Shizuku v13+ prefers the Context constructor; older builds use the no-arg one.
 */
class VolumePrivilegedService : IVolumePrivilegedService.Stub {
    private val context: Context
    private val audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var storedVolumes: MutableMap<String, Float> = mutableMapOf()
    /** Packages observed playing with [android.media.AudioAttributes.USAGE_GAME]. */
    private val usageGamePackages = mutableSetOf<String>()
    private var watcherRegistered = false
    @Volatile
    private var playbackListener: IPlaybackChangeListener? = null

    private val sticky = StickyVolumeScheduler(mainHandler) {
        applyStoredToConfigs(audioManager.activePlaybackConfigurations)
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            applyStoredToConfigs(configs.orEmpty())
            sticky.scheduleBurst()
            notifyPlaybackChanged()
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
            setPlaybackChangeListener(null)
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
            noteGameUsage(pkg, config)
            // Keep muted / stored packages even when isActive becomes false after setVolume(0).
            val kept = volumeForPackage(pkg) != null
            if (!isPlayingLike(config) && !kept) continue
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
        val clamped = volume.coerceIn(0f, VolumePreferences.MAX_VOLUME)
        storedVolumes[packageName] = clamped
        val isGamesGroup = packageName == VolumePreferences.GAMES_GROUP_KEY
        for (config in audioManager.activePlaybackConfigurations) {
            val pkg = packageForConfig(config) ?: continue
            noteGameUsage(pkg, config)
            val match = if (isGamesGroup) {
                isGamePackage(pkg)
            } else {
                pkg == packageName
            }
            if (!match) continue
            // IPlayer/Shaper first — AppOps is async best-effort and must not block binder.
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
        syncGameStream(audioManager.activePlaybackConfigurations)
        sticky.scheduleBurst()
    }

    override fun applyStoredVolumes(stored: Bundle?) {
        storedVolumes.clear()
        if (stored != null) {
            for (key in stored.keySet()) {
                storedVolumes[key] = stored.getFloat(key, 1f).coerceIn(0f, VolumePreferences.MAX_VOLUME)
            }
        }
        applyStoredToConfigs(audioManager.activePlaybackConfigurations)
        syncHardMutesFromStored()
        sticky.scheduleBurst()
    }

    override fun registerPlaybackWatcher() {
        if (watcherRegistered) return
        audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        watcherRegistered = true
        applyStoredToConfigs(audioManager.activePlaybackConfigurations)
        syncHardMutesFromStored()
        sticky.scheduleBurst()
        sticky.startPeriodic()
    }

    override fun unregisterPlaybackWatcher() {
        if (!watcherRegistered) return
        sticky.stop()
        try {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterPlaybackWatcher failed", t)
        }
        watcherRegistered = false
    }

    override fun setPlaybackChangeListener(listener: IPlaybackChangeListener?) {
        playbackListener = listener
    }

    private fun notifyPlaybackChanged() {
        try {
            playbackListener?.onPlaybackChanged()
        } catch (t: Throwable) {
            Log.w(TAG, "playback listener failed", t)
            playbackListener = null
        }
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
        var streamTarget: Float? = null
        for (config in configs) {
            if (!isPlayingLike(config)) continue
            val pkg = packageForConfig(config) ?: continue
            if (!isGamePackage(pkg) && !PlayerVolumeApplier.isSoundPoolType(config)) continue
            val vol = volumeForPackage(pkg) ?: continue
            val g = vol.coerceIn(0f, 1f)
            streamTarget = streamTarget?.let { minOf(it, g) } ?: g
        }
        GameStreamScaler.sync(audioManager, streamTarget)
    }

    private fun syncHardMutesFromStored() {
        if (storedVolumes.isEmpty()) return
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
            for (config in audioManager.activePlaybackConfigurations) {
                val pkg = packageForConfig(config) ?: continue
                noteGameUsage(pkg, config)
                if (isGamePackage(pkg)) {
                    applyVolumeToPackage(pkg, gamesGroup)
                }
            }
        }
    }

    private fun applyVolumeToPackage(pkg: String, volume: Float) {
        AppOpsPlayAudioMute.setMuted(context, pkg, volume <= 0f)
    }

    private fun noteGameUsage(pkg: String, config: AudioPlaybackConfiguration) {
        try {
            if (PlaybackGrouping.isGameUsage(config.audioAttributes.usage)) {
                usageGamePackages.add(pkg)
            }
        } catch (_: Throwable) {
            // older stubs
        }
    }

    private fun isGamePackage(pkg: String): Boolean =
        usageGamePackages.contains(pkg) ||
            PlaybackGrouping.isGame(context.packageManager, pkg)

    /**
     * Games: per-package entry wins when present (overlay may write it independently).
     * Otherwise fall back to [VolumePreferences.GAMES_GROUP_KEY].
     */
    private fun volumeForPackage(pkg: String): Float? {
        storedVolumes[pkg]?.let { return it }
        val gamesGroup = storedVolumes[VolumePreferences.GAMES_GROUP_KEY] ?: return null
        return if (isGamePackage(pkg)) gamesGroup else null
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
