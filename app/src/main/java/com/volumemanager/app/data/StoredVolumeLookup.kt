package com.volumemanager.app.data

import android.content.pm.PackageManager

/**
 * Resolves linear volume from prefs with games-group inheritance.
 * Does not write defaults — callers decide when to persist.
 */
object StoredVolumeLookup {
    fun get(
        preferences: VolumePreferences,
        pm: PackageManager,
        packageName: String,
    ): Float? {
        if (preferences.hasVolume(packageName)) {
            return preferences.getVolume(packageName)
        }
        if (packageName != VolumePreferences.GAMES_GROUP_KEY &&
            preferences.hasVolume(VolumePreferences.GAMES_GROUP_KEY) &&
            PlaybackGrouping.isGame(pm, packageName)
        ) {
            return preferences.getVolume(VolumePreferences.GAMES_GROUP_KEY)
        }
        return null
    }

    /** For stream ceiling rescale: inherit games group, else assume unity gain. */
    fun getOrUnity(
        preferences: VolumePreferences,
        pm: PackageManager,
        packageName: String,
    ): Float = get(preferences, pm, packageName) ?: 1f
}
