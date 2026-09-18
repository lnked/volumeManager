package com.volumemanager.app.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.volumemanager.app.R
import com.volumemanager.app.VolumeController
import com.volumemanager.app.VolumeManagerApp
import com.volumemanager.app.a11y.VolumeAccessibilityService
import com.volumemanager.app.root.RootVolumeController

class MainActivity : AppCompatActivity(), VolumeController.Listener {
    private lateinit var statusA11y: TextView
    private lateinit var statusBackend: TextView
    private lateinit var statusService: TextView
    private lateinit var controller: RootVolumeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusA11y = findViewById(R.id.statusA11y)
        statusBackend = findViewById(R.id.statusBackend)
        statusService = findViewById(R.id.statusService)

        controller = VolumeManagerApp.instance.volumeController as RootVolumeController
        controller.addListener(this)

        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            controller.requestRoot()
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            refreshStatus()
            if (controller.isRootGranted()) {
                controller.bindWithRetry()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (controller.isRootGranted()) {
            controller.bindWithRetry()
        }
    }

    override fun onDestroy() {
        controller.removeListener(this)
        super.onDestroy()
    }

    override fun onAvailabilityChanged(available: Boolean) {
        runOnUiThread { refreshStatus() }
    }

    override fun onServiceReady(ready: Boolean) {
        runOnUiThread { refreshStatus() }
    }

    private fun refreshStatus() {
        val a11yOn = VolumeAccessibilityService.isEnabled() || isAccessibilityEnabled()
        statusA11y.text = getString(
            if (a11yOn) R.string.status_a11y_on else R.string.status_a11y_off,
        )
        statusBackend.text = when {
            controller.isRootGranted() -> getString(R.string.status_backend_ok)
            controller.unavailableReason() == RootVolumeController.UnavailableReason.ROOT_DENIED ->
                getString(R.string.status_backend_denied)
            else -> getString(R.string.status_backend_needed)
        }
        statusService.text = getString(
            if (controller.isAvailable()) R.string.status_service_ready
            else R.string.status_service_waiting,
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, VolumeAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
