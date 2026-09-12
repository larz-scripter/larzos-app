package com.larzos.os

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

/**
 * Lets the user grant, one time each, the two things beyond the guest's own
 * proot sandbox: shared storage (MANAGE_EXTERNAL_STORAGE) and a shell-UID
 * command bridge (Shizuku, via LarzPrivService). Reached from the gear
 * button in TerminalActivity's extra-keys row.
 */
class SystemAccessActivity : AppCompatActivity() {

    private lateinit var storageStatus: TextView
    private lateinit var storageButton: Button
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuButton: Button

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system_access)
        storageStatus = findViewById(R.id.storage_status)
        storageButton = findViewById(R.id.storage_button)
        shizukuStatus = findViewById(R.id.shizuku_status)
        shizukuButton = findViewById(R.id.shizuku_button)

        storageButton.setOnClickListener { openStorageSettings() }
        shizukuButton.setOnClickListener {
            if (!ShizukuBridge.isAvailable) {
                shizukuStatus.text = "Shizuku isn't running yet - follow the steps below, then tap Connect."
            } else {
                ShizukuBridge.requestPermission()
            }
        }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    private fun refresh() {
        val env = (application as LarzApp).env
        storageStatus.text = if (env.sharedStorageAvailable)
            "Granted - ~/storage/shared is live in the shell." else "Not granted yet."
        storageButton.text = if (env.sharedStorageAvailable) "Manage" else "Grant storage access"

        shizukuStatus.text = when {
            ShizukuBridge.hasPermission -> "Connected - `larz-priv <command>` works in the shell."
            ShizukuBridge.isAvailable -> "Shizuku is running - tap Connect to grant LarzOS permission."
            else -> "Shizuku isn't running on this device yet."
        }
        shizukuButton.text = if (ShizukuBridge.hasPermission) "Reconnect" else "Connect Shizuku"
    }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", packageName, null)
            )
            runCatching { startActivity(intent) }
                .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 1
            )
        }
    }
}
