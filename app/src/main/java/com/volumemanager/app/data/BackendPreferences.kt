package com.volumemanager.app.data

import android.content.Context
import android.content.SharedPreferences

enum class BackendKind {
    ROOT,
    SUI,
}

/**
 * Persists which privileged backend the user selected (single APK, both included).
 * Until the user (or auto-detect) writes a value, [hasExplicitBackend] is false.
 */
class BackendPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasExplicitBackend(): Boolean = prefs.contains(KEY_BACKEND)

    fun getBackend(): BackendKind {
        return when (prefs.getString(KEY_BACKEND, BackendKind.ROOT.name)) {
            BackendKind.SUI.name -> BackendKind.SUI
            else -> BackendKind.ROOT
        }
    }

    fun setBackend(kind: BackendKind) {
        prefs.edit().putString(KEY_BACKEND, kind.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "backend"
        private const val KEY_BACKEND = "kind"
    }
}
