package com.volumemanager.app.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.volumemanager.app.DualVolumeController
import com.volumemanager.app.R
import com.volumemanager.app.VolumeController
import com.volumemanager.app.VolumeManagerApp
import com.volumemanager.app.a11y.VolumeAccessibilityService
import com.volumemanager.app.data.BackendKind
import com.volumemanager.app.root.RootVolumeController
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity(), VolumeController.Listener {
    private lateinit var stepBackendMark: TextView
    private lateinit var stepBackendText: TextView
    private lateinit var stepA11yMark: TextView
    private lateinit var stepA11yText: TextView
    private lateinit var stepReadyMark: TextView
    private lateinit var stepReadyText: TextView
    private lateinit var statusBackendDetail: TextView
    private lateinit var btnGrant: Button
    private lateinit var backendGroup: RadioGroup
    private lateinit var controller: DualVolumeController

    private var awaitingSuiPermission = false

    private val suiPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (!awaitingSuiPermission) return@OnRequestPermissionResultListener
            awaitingSuiPermission = false
            if (grantResult != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.toast_sui_denied, Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
            tryBindActive()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val scroll = findViewById<ScrollView>(R.id.mainScroll)
        val contentPadH = resources.getDimensionPixelSize(R.dimen.main_content_padding)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = contentPadH,
                top = bars.top + contentPadH,
                right = contentPadH,
                bottom = bars.bottom + contentPadH,
            )
            insets
        }

        stepBackendMark = findViewById(R.id.stepBackendMark)
        stepBackendText = findViewById(R.id.stepBackendText)
        stepA11yMark = findViewById(R.id.stepA11yMark)
        stepA11yText = findViewById(R.id.stepA11yText)
        stepReadyMark = findViewById(R.id.stepReadyMark)
        stepReadyText = findViewById(R.id.stepReadyText)
        statusBackendDetail = findViewById(R.id.statusBackendDetail)
        btnGrant = findViewById(R.id.btnGrant)
        backendGroup = findViewById(R.id.backendGroup)

        controller = VolumeManagerApp.instance.volumeController
        controller.addListener(this)
        Shizuku.addRequestPermissionResultListener(suiPermissionListener)

        backendGroup.check(
            when (controller.currentBackend()) {
                BackendKind.ROOT -> R.id.backendRoot
                BackendKind.SUI -> R.id.backendSui
            },
        )
        backendGroup.setOnCheckedChangeListener { _, checkedId ->
            val next = when (checkedId) {
                R.id.backendSui -> BackendKind.SUI
                else -> BackendKind.ROOT
            }
            controller.selectBackend(next)
            refreshStatus()
            tryBindActive()
        }

        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener { openAccessibilitySettings() }
        btnGrant.setOnClickListener { onGrantClicked() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            refreshStatus()
            tryBindActive()
        }
        findViewById<Button>(R.id.btnGitHub).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL)))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        tryBindActive()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(suiPermissionListener)
        controller.removeListener(this)
        super.onDestroy()
    }

    override fun onAvailabilityChanged(available: Boolean) {
        runOnUiThread { refreshStatus() }
    }

    override fun onServiceReady(ready: Boolean) {
        runOnUiThread { refreshStatus() }
    }

    private fun onGrantClicked() {
        when (controller.currentBackend()) {
            BackendKind.ROOT -> {
                controller.root.requestRoot { ok ->
                    if (!ok) {
                        Toast.makeText(this, R.string.toast_root_denied, Toast.LENGTH_SHORT).show()
                    }
                    refreshStatus()
                }
            }
            BackendKind.SUI -> {
                val sui = controller.sui
                if (!sui.isShizukuRunning()) {
                    Toast.makeText(this, R.string.toast_sui_needed, Toast.LENGTH_SHORT).show()
                    openSuiOrShizuku()
                } else if (!sui.hasPermission()) {
                    awaitingSuiPermission = true
                    sui.requestPermission()
                } else {
                    tryBindActive()
                }
            }
        }
    }

    private fun tryBindActive() {
        if (controller.canAttemptBind()) {
            controller.bindWithRetry()
        }
    }

    private fun refreshStatus() {
        val a11yOn = VolumeAccessibilityService.isEnabled() || isAccessibilityEnabled()
        val backendReady = isBackendAccessReady()
        val serviceReady = controller.isAvailable()

        bindStep(
            mark = stepBackendMark,
            text = stepBackendText,
            ok = backendReady,
            okRes = R.string.step_backend_ok,
            needRes = R.string.step_backend_need,
        )
        bindStep(
            mark = stepA11yMark,
            text = stepA11yText,
            ok = a11yOn,
            okRes = R.string.step_a11y_ok,
            needRes = R.string.step_a11y_need,
        )
        bindStep(
            mark = stepReadyMark,
            text = stepReadyText,
            ok = a11yOn && serviceReady,
            okRes = R.string.step_ready_ok,
            needRes = R.string.step_ready_need,
        )

        when (controller.currentBackend()) {
            BackendKind.ROOT -> {
                btnGrant.setText(R.string.btn_grant_root)
                val root = controller.root
                statusBackendDetail.text = when {
                    root.isRootGranted() -> getString(R.string.status_root_ok)
                    root.unavailableReason() == RootVolumeController.UnavailableReason.ROOT_DENIED ->
                        getString(R.string.status_root_denied)
                    else -> getString(R.string.status_root_needed)
                }
            }
            BackendKind.SUI -> {
                btnGrant.setText(R.string.btn_grant_sui)
                val sui = controller.sui
                statusBackendDetail.text = when {
                    !sui.isShizukuRunning() -> getString(R.string.status_sui_needed)
                    !sui.hasPermission() -> getString(R.string.status_sui_denied)
                    else -> getString(R.string.status_sui_ok)
                }
            }
        }
    }

    private fun isBackendAccessReady(): Boolean = when (controller.currentBackend()) {
        BackendKind.ROOT -> controller.root.isRootGranted()
        BackendKind.SUI -> controller.sui.isShizukuRunning() && controller.sui.hasPermission()
    }

    private fun bindStep(
        mark: TextView,
        text: TextView,
        ok: Boolean,
        okRes: Int,
        needRes: Int,
    ) {
        mark.text = getString(if (ok) R.string.checklist_mark_ok else R.string.checklist_mark_need)
        mark.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.status_ok else R.color.status_need),
        )
        text.setText(if (ok) okRes else needRes)
        text.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.text_primary else R.color.text_secondary),
        )
    }

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, VolumeAccessibilityService::class.java)
        // Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS (API 31+); constant may be @hide on some SDKs.
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        }
        try {
            startActivity(details)
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, VolumeAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openSuiOrShizuku() {
        val candidates = listOf(
            "moe.shizuku.privileged.api",
            "rikka.sui",
        )
        for (pkg in candidates) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                startActivity(launch)
                return
            }
        }
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/RikkaApps/Sui"),
            ),
        )
    }

    companion object {
        private const val GITHUB_RELEASES_URL =
            "https://github.com/lnked/volumeManager/releases"
    }
}
