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
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                status.text = "Overlay permission is required."
            }
        }

    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                status.text = "Screen capture permission was cancelled."
                return@registerForActivityResult
            }

            val serviceIntent = Intent(this, OverlayTranslationService::class.java).apply {
                action = OverlayTranslationService.ACTION_START
                putExtra(OverlayTranslationService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayTranslationService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            status.text = "Started. Look for the green OCR status pill over other apps."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestNotificationPermissionIfNeeded()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(TextView(this).apply {
            text = "Japanese Screen Translator"
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text = "PP-OCRv6 Tiny scans the screen locally. Every OCR box is shown: Japanese text is covered with English, while other text gets a yellow highlight. Touches always pass through."
            textSize = 16f
            setPadding(0, dp(18), 0, dp(12))
        })

        root.addView(TextView(this).apply {
            text = "After restarting the app or phone, tap Start again and approve screen capture. The downloaded translation model stays installed."
            textSize = 14f
            setPadding(0, 0, 0, dp(22))
        })

        root.addView(Button(this).apply {
            text = "Start"
            setOnClickListener { beginPermissionFlow() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startService(Intent(this@MainActivity, OverlayTranslationService::class.java).apply {
                    action = OverlayTranslationService.ACTION_STOP
                })
                status.text = "Stopped."
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })

        status = TextView(this).apply {
            text = "Ready. Tap Start for each screen-capture session."
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(status)
        return root
    }

    private fun beginPermissionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Grant display-over-other-apps permission, then return."
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        capturePermissionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
