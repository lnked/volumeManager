package com.volumemanager.app

import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Best-effort AppOps PLAY_AUDIO mute on a background thread.
 * Never call [ProcessBuilder]/appops`] from the binder thread — on some OEMs it
 * deadlocks the Shizuku UserService (`wait_for_vfork_done`).
 */
object AppOpsPlayAudioMute {
    private const val TAG = "AppOpsPlayMute"

    private val worker: Handler by lazy {
        val t = HandlerThread("appops-mute").also { it.start() }
        Handler(t.looper)
    }

    fun setMuted(context: Context, packageName: String, muted: Boolean) {
        if (packageName.startsWith("__")) return
        val appContext = context.applicationContext ?: context
        worker.post {
            setModeViaManager(appContext, packageName, muted)
        }
    }

    private fun setModeViaManager(context: Context, packageName: String, muted: Boolean): Boolean {
        return try {
            val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (muted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
            val op = resolvePlayAudioOp()
            val setMode = AppOpsManager::class.java.getMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            setMode.invoke(appOps, op, uid, packageName, mode)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setMode failed for $packageName muted=$muted", t)
            false
        }
    }

    private fun resolvePlayAudioOp(): Int {
        try {
            val m = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
            return m.invoke(null, "android:play_audio") as Int
        } catch (_: Throwable) {
            // ignore
        }
        return try {
            AppOpsManager::class.java.getField("OP_PLAY_AUDIO").getInt(null)
        } catch (_: Throwable) {
            28
        }
    }
}
