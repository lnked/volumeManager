package com.volumemanager.app.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.volumemanager.app.IVolumePrivilegedService
import com.volumemanager.app.PlaybackAppInfo
import com.volumemanager.app.R
import com.volumemanager.app.VolumeController
import com.volumemanager.app.data.VolumePreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Binds libsu [VolumeRootService] and exposes per-app volume to the UI process.
 * Without root grant, callers fall back to global STREAM_MUSIC.
 */
class RootVolumeController(
    private val context: Context,
    private val preferences: VolumePreferences,
) : VolumeController {
    enum class UnavailableReason {
        NO_ROOT,
        ROOT_DENIED,
        SERVICE_NOT_BOUND,
    }

    private val listeners = CopyOnWriteArrayList<VolumeController.Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shellExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var service: IVolumePrivilegedService? = null
    @Volatile
    private var binding = false
    private var bindAttempt = 0

    private val connection = object : ServiceConnection {
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
            Log.i(TAG, "RootService connected name=$name")
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
            Log.w(TAG, "RootService disconnected name=$name")
            service = null
            binding = false
            notifyServiceReady(false)
            notifyAvailability()
        }
    }

    override fun start() {
        notifyAvailability()
        if (isRootGranted()) {
            bindWithRetry()
        }
    }

    override fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        unbind()
        shellExecutor.shutdownNow()
    }

    override fun addListener(listener: VolumeController.Listener) {
        listeners.add(listener)
        listener.onAvailabilityChanged(isAvailable())
        listener.onServiceReady(service != null)
    }

    override fun removeListener(listener: VolumeController.Listener) {
        listeners.remove(listener)
    }

    fun isRootGranted(): Boolean = try {
        Shell.isAppGrantedRoot() == true || Shell.getCachedShell()?.isRoot == true
    } catch (_: Throwable) {
        false
    }

    override fun canAttemptBind(): Boolean = isRootGranted()

    override fun isAvailable(): Boolean = isRootGranted() && service != null

    fun unavailableReason(): UnavailableReason? {
        val granted = Shell.isAppGrantedRoot()
        when (granted) {
            false -> return UnavailableReason.ROOT_DENIED
            null -> {
                if (!isRootGranted()) return UnavailableReason.NO_ROOT
            }
            true -> Unit
        }
        if (service == null) return UnavailableReason.SERVICE_NOT_BOUND
        return null
    }

    override fun fallbackHintRes(): Int = when (unavailableReason()) {
        UnavailableReason.NO_ROOT -> R.string.overlay_hint_primary
        UnavailableReason.ROOT_DENIED -> R.string.overlay_hint_denied
        UnavailableReason.SERVICE_NOT_BOUND -> R.string.overlay_hint_service_not_bound
        null -> R.string.overlay_fallback_hint
    }

    /**
     * Triggers Magisk/su permission prompt on a background thread, then binds RootService.
     */
    fun requestRoot() {
        shellExecutor.execute {
            try {
                val shell = Shell.getShell()
                val ok = shell.isRoot
                Log.i(TAG, "requestRoot isRoot=$ok")
                mainHandler.post {
                    notifyAvailability()
                    if (ok) bindWithRetry()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "requestRoot failed", t)
                mainHandler.post { notifyAvailability() }
            }
        }
    }

    override fun bind() {
        if (!isRootGranted()) {
            Log.d(TAG, "bind skipped — root not granted")
            return
        }
        if (service != null) return
        if (binding) {
            Log.d(TAG, "bind already in progress")
            return
        }
        binding = true
        bindAttempt++
        Log.i(TAG, "RootService.bind attempt=$bindAttempt")
        try {
            val intent = Intent(context, VolumeRootService::class.java)
            RootService.bind(intent, connection)
        } catch (t: Throwable) {
            binding = false
            Log.e(TAG, "RootService.bind failed attempt=$bindAttempt", t)
        }
    }

    override fun bindWithRetry() {
        bind()
        RETRY_DELAYS_MS.forEach { delayMs ->
            mainHandler.postDelayed({
                if (service != null) return@postDelayed
                if (!isRootGranted()) return@postDelayed
                Log.w(TAG, "RootService still null after ${delayMs}ms — retry bind")
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
            RootService.unbind(connection)
        } catch (t: Throwable) {
            Log.w(TAG, "RootService.unbind failed", t)
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
        private const val TAG = "RootVolCtrl"
        private val RETRY_DELAYS_MS = longArrayOf(400L, 1200L, 3000L)
    }
}
