package com.volumemanager.app

import android.app.Application
import com.volumemanager.app.data.VolumePreferences
import com.volumemanager.app.shizuku.ShizukuVolumeController

class VolumeManagerApp : Application() {
    lateinit var volumeController: VolumeController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        volumeController = ShizukuVolumeController(this, VolumePreferences(this)).also { it.start() }
    }

    companion object {
        lateinit var instance: VolumeManagerApp
            private set
    }
}
