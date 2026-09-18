package com.volumemanager.app

import android.app.Application
import com.topjohnwu.superuser.Shell
import com.volumemanager.app.data.VolumePreferences
import com.volumemanager.app.root.RootVolumeController

class VolumeManagerApp : Application() {
    lateinit var volumeController: VolumeController
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
        volumeController = RootVolumeController(this, VolumePreferences(this)).also { it.start() }
    }

    companion object {
        lateinit var instance: VolumeManagerApp
            private set
    }
}
