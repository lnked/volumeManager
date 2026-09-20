package com.volumemanager.app

import android.app.Application
import com.topjohnwu.superuser.Shell
import com.volumemanager.app.data.BackendPreferences
import com.volumemanager.app.data.VolumePreferences
import com.volumemanager.app.root.RootVolumeController
import com.volumemanager.app.shizuku.ShizukuVolumeController

class VolumeManagerApp : Application() {
    lateinit var volumeController: DualVolumeController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10),
        )
        val prefs = VolumePreferences(this)
        volumeController = DualVolumeController(
            backendPrefs = BackendPreferences(this),
            root = RootVolumeController(this, prefs),
            sui = ShizukuVolumeController(this, prefs),
        ).also { it.start() }
    }

    companion object {
        lateinit var instance: VolumeManagerApp
            private set
    }
}
