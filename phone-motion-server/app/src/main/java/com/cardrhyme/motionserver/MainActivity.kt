package com.cardrhyme.motionserver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var statsText: TextView
    private lateinit var batteryModeText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNotificationPermissionIfNeeded()
        if (getSharedPreferences("motion_server", MODE_PRIVATE).getBoolean("enabled", true)) {
            startMonitoring()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(15, 15, 15)
        window.navigationBarColor = Color.rgb(15, 15, 15)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        fun text(size: Float, color: Int = Color.WHITE): TextView = TextView(this).apply {
            textSize = size
            setTextColor(color)
        }

        root.addView(text(26f).apply {
            this.text = "Motion Server"
            setPadding(0, 0, 0, dp(18))
        })

        statusText = text(18f)
        root.addView(statusText)

        urlText = text(17f, Color.rgb(135, 190, 255)).apply {
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(14))
        }
        root.addView(urlText)

        statsText = text(15f, Color.rgb(205, 205, 205)).apply {
            setPadding(0, 0, 0, dp(18))
        }
        root.addView(statsText)

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow.addView(Button(this).apply {
            text = "Start"
            setOnClickListener { startMonitoring() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        buttonRow.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopMonitoring() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        root.addView(buttonRow)

        root.addView(Button(this).apply {
            text = "Allow unrestricted battery"
            setOnClickListener { requestUnrestrictedBattery() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        batteryModeText = text(14f, Color.rgb(185, 185, 185)).apply {
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(batteryModeText)

        root.addView(text(13f, Color.rgb(155, 155, 155)).apply {
            this.text = "Debug endpoint: /api/debug"
            setPadding(0, dp(14), 0, 0)
        })

        setContentView(root)
    }

    private fun startMonitoring() {
        getSharedPreferences("motion_server", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        try {
            startForegroundService(Intent(this, MotionService::class.java))
        } catch (e: Exception) {
            statusText.text = "Start failed: ${e.message ?: e.javaClass.simpleName}"
        }
        refreshUi()
    }

    private fun stopMonitoring() {
        getSharedPreferences("motion_server", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        stopService(Intent(this, MotionService::class.java))
        refreshUi()
    }

    private fun refreshUi() {
        val ip = NetworkUtil.lanIpv4(this)
        val running = RuntimeState.running
        statusText.text = if (running) "RUNNING" else "STOPPED"
        statusText.setTextColor(if (running) Color.rgb(130, 220, 150) else Color.rgb(230, 135, 135))

        urlText.text = if (ip != null) "http://$ip:${RuntimeState.PORT}/api/state" else "No LAN IPv4 address detected"

        val battery = if (RuntimeState.batteryPercent >= 0) "${RuntimeState.batteryPercent}%" else "unknown"
        val movement = if (RuntimeState.movementId == 0L) {
            "none yet"
        } else {
            val age = ((SystemClock.elapsedRealtime() - RuntimeState.lastMovementElapsedMs).coerceAtLeast(0L) / 100) / 10.0
            "#${RuntimeState.movementId}, ${age}s ago"
        }
        val sensorAge = if (RuntimeState.lastSensorEventElapsedMs == 0L) "none" else "${SystemClock.elapsedRealtime() - RuntimeState.lastSensorEventElapsedMs} ms ago"
        val error = RuntimeState.lastError?.let { "\nError: $it" } ?: ""

        statsText.text = "Battery: $battery\n" +
            "Last movement: $movement\n" +
            "Accel callbacks: ${RuntimeState.accelEvents}\n" +
            "Gyro callbacks: ${RuntimeState.gyroEvents}\n" +
            "Last sensor callback: $sensorAge$error"

        val pm = getSystemService(PowerManager::class.java)
        batteryModeText.text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            "Battery optimization: unrestricted/exempt"
        } else {
            "Battery optimization: Android may suspend background work"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun requestUnrestrictedBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
