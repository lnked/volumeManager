package com.volumemanager.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.volumemanager.app.BuildConfig
import com.volumemanager.app.IVolumePrivilegedService
import com.volumemanager.app.PlaybackAppInfo
import com.volumemanager.app.R
import com.volumemanager.app.VolumeController
import com.volumemanager.app.data.VolumePreferences
import com.volumemanager.app.privileged.VolumePrivilegedService
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Binds Shizuku/Sui UserService and exposes per-app volume operations to the UI process.
 */
class ShizukuVolumeController(
    private val context: Context,
    private val preferences: VolumePreferences,
) : VolumeController {
    enum class UnavailableReason {
        SHIZUKU_OFF,
        NO_PERMISSION,
        SERVICE_NOT_BOUND,
    }

    private val listeners = CopyOnWriteArrayList<VolumeController.Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var service: IVolumePrivilegedService? = null
    @Volatile
    private var binding = false
    private var bindAttempt = 0

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "binder received; running=${isShizukuRunning()} perm=${hasPermission()}")
        notifyAvailability()
        if (hasPermission()) {
            binding = false
            bindWithRetry()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "binder dead")
        service = null
        binding = false
        notifyAvailability()
        notifyServiceReady(false)
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            Log.i(TAG, "permission result=$grantResult")
            notifyAvailability()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                binding = false
                bindWithRetry()
            }
        }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                Log.e(TAG, "onServiceConnected: null/dead binder name=$name")
                service = null
                binding = false
                notifyServiceReady(false)
                notifyAvailability()
                return
            }
            val svc = IVolumePrivilegedService.Stub.asInterface(binder)
            service = svc
            binding = false
            bindAttempt = 0
            Log.i(TAG, "UserService connected name=$name")
            try {
                svc.applyStoredVolumes(preferences.allVolumes().toBundle())
                svc.registerPlaybackWatcher()
            } catch (t: Throwable) {
                Log.w(TAG, "applyStoredVolumes/register failed", t)
            }
            notifyServiceReady(true)
            notifyAvailability()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "UserService disconnected name=$name")
            service = null
            binding = false
            notifyServiceReady(false)
            notifyAvailability()
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, VolumePrivilegedService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("priv")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .tag("VolumePrivilegedService")

    override fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        notifyAvailability()
        if (isShizukuRunning() && hasPermission()) {
            bindWithRetry()
        }
    }

    override fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        unbind()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    override fun addListener(listener: VolumeController.Listener) {
        listeners.add(listener)
        listener.onAvailabilityChanged(isAvailable())
        listener.onServiceReady(service != null)
    }

    override fun removeListener(listener: VolumeController.Listener) {
        listeners.remove(listener)
    }

    fun isShizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    override fun isAvailable(): Boolean = isShizukuRunning() && hasPermission() && service != null

    override fun canAttemptBind(): Boolean = isShizukuRunning() && hasPermission()

    fun unavailableReason(): UnavailableReason? {
        if (!isShizukuRunning()) return UnavailableReason.SHIZUKU_OFF
        if (!hasPermission()) return UnavailableReason.NO_PERMISSION
        if (service == null) return UnavailableReason.SERVICE_NOT_BOUND
        return null
    }

    override fun fallbackHintRes(): Int = when (unavailableReason()) {
        UnavailableReason.SHIZUKU_OFF -> R.string.overlay_hint_primary
        UnavailableReason.NO_PERMISSION -> R.string.overlay_hint_denied
        UnavailableReason.SERVICE_NOT_BOUND -> R.string.overlay_hint_service_not_bound
        null -> R.string.overlay_fallback_hint
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE) {
        if (!isShizukuRunning()) return
        if (hasPermission()) {
            bindWithRetry()
            return
        }
        Shizuku.requestPermission(requestCode)
    }

    override fun bind() {
        if (!isShizukuRunning() || !hasPermission()) {
            Log.d(
                TAG,
                "bind skipped running=${isShizukuRunning()} perm=${hasPermission()}",
            )
            return
        }
        if (service != null) return
        if (binding) {
            Log.d(TAG, "bind already in progress")
            return
        }
        binding = true
        bindAttempt++
        Log.i(TAG, "bindUserService attempt=$bindAttempt")
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (t: Throwable) {
            binding = false
            Log.e(TAG, "bindUserService failed attempt=$bindAttempt", t)
        }
    }

    /** Bind immediately, then retry if the UserService never connects. */
    override fun bindWithRetry() {
        bind()
        RETRY_DELAYS_MS.forEach { delayMs ->
            mainHandler.postDelayed({
                if (service != null) return@postDelayed
                if (!isShizukuRunning() || !hasPermission()) return@postDelayed
                Log.w(TAG, "UserService still null after ${delayMs}ms — retry bind")
                binding = false
                bind()
            }, delayMs)
        }
    }

    fun unbind() {
        try {
            service?.unregisterPlaybackWatcher()
        } catch (_: Throwable) {
        }
        service = null
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
        } catch (t: Throwable) {
            Log.w(TAG, "unbindUserService failed", t)
        }
        binding = false
        notifyServiceReady(false)
    }

    override fun listActivePlaybacks(): List<PlaybackAppInfo> {
        return try {
            service?.listActivePlaybacks().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "listActivePlaybacks failed", t)
            emptyList()
        }
    }

    override fun setPackageVolume(packageName: String, volume: Float) {
        preferences.setVolume(packageName, volume)
        try {
            service?.setPackageVolume(packageName, volume)
        } catch (t: Throwable) {
            Log.w(TAG, "setPackageVolume failed", t)
        }
    }

    override fun getStoredVolume(packageName: String): Float = preferences.getVolume(packageName)

    private fun notifyAvailability() {
        val available = isAvailable()
        listeners.forEach { it.onAvailabilityChanged(available) }
    }

    private fun notifyServiceReady(ready: Boolean) {
        listeners.forEach { it.onServiceReady(ready) }
    }

    private fun Map<String, Float>.toBundle(): Bundle {
        val b = Bundle()
        forEach { (k, v) -> b.putFloat(k, v) }
        return b
    }

    companion object {
        private const val TAG = "ShizukuVolCtrl"
        const val REQUEST_CODE = 1001
        private val RETRY_DELAYS_MS = longArrayOf(400L, 1200L, 3000L)
    }
}
