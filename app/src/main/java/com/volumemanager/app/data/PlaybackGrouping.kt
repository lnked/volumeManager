package com.volumemanager.app.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import com.volumemanager.app.PlaybackAppInfo

/**
 * Groups active playbacks for overlay: each non-game app is its own column;
 * all games share one column.
 */
data class OverlayPlaybackColumn(
    val packageNames: List<String>,
    /** Package used for icon when not a games group. */
    val iconPackage: String,
    val label: String,
    val isGamesGroup: Boolean,
) {
    val storageKey: String
        get() = if (isGamesGroup) VolumePreferences.GAMES_GROUP_KEY else packageNames.first()
}

object PlaybackGrouping {
    fun isGame(pm: PackageManager, packageName: String): Boolean {
        if (looksLikeGamePackage(packageName)) return true
        return try {
            val ai = pm.getApplicationInfo(packageName, 0)
            if (ai.category == ApplicationInfo.CATEGORY_GAME) return true
            @Suppress("DEPRECATION")
            (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Runtime signal from active players (many games omit CATEGORY_GAME). */
    fun isGameUsage(usage: Int): Boolean = usage == AudioAttributes.USAGE_GAME

    fun looksLikeGamePackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p.startsWith("game.") ||
            p.contains(".game.") ||
            p.endsWith(".game") ||
            p.contains("games")
    }

    fun columns(
        apps: List<PlaybackAppInfo>,
        pm: PackageManager,
        gamesLabel: String,
        preferredPackage: String? = null,
    ): List<OverlayPlaybackColumn> {
        if (apps.isEmpty()) return emptyList()

        val games = mutableListOf<PlaybackAppInfo>()
        val others = mutableListOf<PlaybackAppInfo>()
        for (app in apps) {
            if (isGame(pm, app.packageName)) games.add(app) else others.add(app)
        }

        val result = ArrayList<OverlayPlaybackColumn>(others.size + if (games.isEmpty()) 0 else 1)
        for (app in others) {
            result += OverlayPlaybackColumn(
                packageNames = listOf(app.packageName),
                iconPackage = app.packageName,
                label = app.label,
                isGamesGroup = false,
            )
        }
        if (games.isNotEmpty()) {
            result += OverlayPlaybackColumn(
                packageNames = games.map { it.packageName },
                iconPackage = games.first().packageName,
                label = gamesLabel,
                isGamesGroup = true,
            )
        }

        if (preferredPackage.isNullOrEmpty()) return result
        val preferredIndex = result.indexOfFirst { column ->
            column.packageNames.any { it == preferredPackage } ||
                (column.isGamesGroup && isGame(pm, preferredPackage))
        }
        if (preferredIndex <= 0) return result
        val preferred = result.removeAt(preferredIndex)
        result.add(0, preferred)
        return result
    }
}
