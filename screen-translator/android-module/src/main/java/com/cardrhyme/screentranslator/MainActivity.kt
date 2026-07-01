package com.cardrhyme.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) prepareOverlay() else status.text = "Overlay permission required"
        }

    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                stopTranslator()
                status.text = "Screen capture cancelled"
                return@registerForActivityResult
            }
            ContextCompat.startForegroundService(
                this,
                Intent(this, OverlayTranslationService::class.java).apply {
                    action = OverlayTranslationService.ACTION_START
                    putExtra(OverlayTranslationService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayTranslationService.EXTRA_RESULT_DATA, data)
                },
            )
            status.text = "Running"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(TextView(this).apply { text = "Japanese Screen Translator"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Tap Start. You should see OVERLAY OK before choosing full-screen capture."
            textSize = 16f
            setPadding(0, dp(18), 0, dp(22))
        })
        root.addView(Button(this).apply {
            text = "Start"
            setOnClickListener { beginStart() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopTranslator(); status.text = "Stopped" }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        status = TextView(this).apply { text = "Ready"; setPadding(0, dp(20), 0, 0) }
        root.addView(status)
        return root
    }

    private fun beginStart() {
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        } else {
            prepareOverlay()
        }
    }

    private fun prepareOverlay() {
        status.text = "Preparing overlay"
        startService(Intent(this, OverlayTranslationService::class.java).apply {
            action = OverlayTranslationService.ACTION_PREPARE
        })
        window.decorView.postDelayed({
            val manager = getSystemService(MediaProjectionManager::class.java)
            capturePermissionLauncher.launch(manager.createScreenCaptureIntent())
        }, 700L)
    }

    private fun stopTranslator() {
        startService(Intent(this, OverlayTranslationService::class.java).apply {
            action = OverlayTranslationService.ACTION_STOP
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
