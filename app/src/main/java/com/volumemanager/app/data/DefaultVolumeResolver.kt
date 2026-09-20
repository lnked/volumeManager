package com.volumemanager.app.data

import android.content.Context
import android.media.AudioManager
import android.content.pm.PackageManager

/**
 * Resolves default linear volume for packages with no stored `vol_*` entry.
 *
 * Order: category setting (games / other) → STREAM_MUSIC ratio if «as system».
 */
object DefaultVolumeResolver {
    fun systemMediaRatio(audioManager: AudioManager): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max)
            .coerceIn(0f, 1f)
    }

    fun resolve(
        packageName: String,
        pm: PackageManager,
        audioManager: AudioManager,
        settings: AppSettingsPreferences,
    ): Float {
        if (packageName == VolumePreferences.STREAM_MUSIC_KEY) {
            return systemMediaRatio(audioManager)
        }
        val games = packageName == VolumePreferences.GAMES_GROUP_KEY ||
            PlaybackGrouping.isGame(pm, packageName)
        return if (games) {
            if (settings.isGamesAsSystem()) systemMediaRatio(audioManager)
            else settings.getGamesDefaultVolume()
        } else {
            if (settings.isOtherAsSystem()) systemMediaRatio(audioManager)
            else settings.getOtherDefaultVolume()
        }
    }

    fun resolve(
        context: Context,
        packageName: String,
        settings: AppSettingsPreferences,
    ): Float {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return resolve(packageName, context.packageManager, am, settings)
    }
}
