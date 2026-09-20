package com.volumemanager.app.a11y

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore

/**
 * Detects packages that typically bind Volume− to shutter / camera controls.
 * When such an app is foreground, volume keys must not be consumed by the a11y service.
 */
object CameraAppDetector {
    fun isCameraPackage(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        if (isRoleCamera(context, packageName)) return true
        if (looksLikeCameraPackageName(packageName)) return true
        if (handlesCaptureIntent(context, packageName)) return true
        return false
    }

    private fun isRoleCamera(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return false
            // ROLE_CAMERA = "android.app.role.CAMERA" (API 29+)
            val holders = rm.getRoleHolders("android.app.role.CAMERA")
            holders.contains(packageName)
        } catch (_: Throwable) {
            false
        }
    }

    private fun looksLikeCameraPackageName(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p.contains(".camera") ||
            p.endsWith("camera") ||
            p.contains("googlecamera") ||
            p.startsWith("com.asus.camera") ||
            p.startsWith("com.sec.android.app.camera") ||
            p.startsWith("com.android.camera") ||
            p.startsWith("org.codeaurora.snapcam") ||
            p.startsWith("com.motorola.camera") ||
            p.startsWith("com.oneplus.camera") ||
            p.startsWith("com.oplus.camera") ||
            p.startsWith("com.miui.camera") ||
            p.startsWith("com.huawei.camera") ||
            p.startsWith("com.honor.camera")
    }

    private fun handlesCaptureIntent(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intents = listOf(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(MediaStore.ACTION_VIDEO_CAPTURE),
        )
        for (intent in intents) {
            val matches = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (_: Throwable) {
                emptyList()
            }
            if (matches.any { it.activityInfo?.packageName == packageName }) return true
        }
        return false
    }
}
