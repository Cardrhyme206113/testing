package com.cardrhyme.motionserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MotionService : Service(), SensorEventListener {
    companion object {
        private const val CHANNEL_ID = "motion_server"
        private const val NOTIFICATION_ID = 42061
        private const val SAMPLE_PERIOD_US = 20_000 // ~50 Hz

        private const val FAST_ACCEL_THRESHOLD = 0.13f      // m/s^2 linear acceleration
        private const val FAST_GYRO_THRESHOLD = 0.12f       // rad/s
        private const val SUBTLE_ACCEL_THRESHOLD = 0.055f   // catches slow hand motion
        private const val SUBTLE_GYRO_THRESHOLD = 0.05f
        private const val STRONG_ACCEL_THRESHOLD = 0.45f
        private const val FAST_EVIDENCE_MS = 60f
        private const val SUBTLE_EVIDENCE_MS = 220f
        private const val MOTION_RELEASE_MS = 500L
    }

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var usingLinearAcceleration = true
    private var wakeLock: PowerManager.WakeLock? = null
    private var httpServer: LocalHttpServer? = null

    private val gravity = FloatArray(3)
    private var gravityInitialized = false
    private var latestGyroMagnitude = 0f
    private var lastAccelTimestampNs = 0L
    private var fastEvidenceMs = 0f
    private var subtleEvidenceMs = 0f
    private var inMotion = false
    private var lastSubtleActivityElapsedMs = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            RuntimeState.batteryPercent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            RuntimeState.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            RuntimeState.batteryTempC = if (tempTenths == Int.MIN_VALUE) Float.NaN else tempTenths / 10f
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeState.running = true
        RuntimeState.startedElapsedMs = SystemClock.elapsedRealtime()
        RuntimeState.lastError = null

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotionServer:SensorAndApi").apply {
            setReferenceCounted(false)
            acquire()
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startSensors()

        try {
            httpServer = LocalHttpServer(applicationContext).also { it.start() }
        } catch (e: Exception) {
            RuntimeState.lastError = "Port ${RuntimeState.PORT} unavailable: ${e.message ?: e.javaClass.simpleName}"
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSharedPreferences("motion_server", MODE_PRIVATE)
            .edit().putBoolean("enabled", true).apply()
        return START_STICKY
    }

    override fun onDestroy() {
        RuntimeState.running = false
        RuntimeState.motionActive = false
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { httpServer?.stop() } catch (_: Exception) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        httpServer = null
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSensors() {
        sensorManager = getSystemService(SensorManager::class.java)

        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (accelSensor == null) {
            usingLinearAcceleration = false
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accelSensor?.let { sensorManager.registerListener(this, it, SAMPLE_PERIOD_US) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SAMPLE_PERIOD_US) }

        if (accelSensor == null) {
            RuntimeState.lastError = "No accelerometer/linear-acceleration sensor available"
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                latestGyroMagnitude = magnitude(event.values[0], event.values[1], event.values[2])
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                processAcceleration(
                    magnitude(event.values[0], event.values[1], event.values[2]),
                    event.timestamp
                )
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (usingLinearAcceleration) return
                if (!gravityInitialized) {
                    gravity[0] = event.values[0]
                    gravity[1] = event.values[1]
                    gravity[2] = event.values[2]
                    gravityInitialized = true
                    return
                }

                // Low-pass gravity estimate; subtract it to approximate linear acceleration.
                val alpha = 0.92f
                for (i in 0..2) gravity[i] = alpha * gravity[i] + (1f - alpha) * event.values[i]
                val x = event.values[0] - gravity[0]
                val y = event.values[1] - gravity[1]
                val z = event.values[2] - gravity[2]
                processAcceleration(magnitude(x, y, z), event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun processAcceleration(accelMagnitude: Float, timestampNs: Long) {
        val dtMs = if (lastAccelTimestampNs == 0L) {
            20f
        } else {
            ((timestampNs - lastAccelTimestampNs) / 1_000_000f).coerceIn(5f, 50f)
        }
        lastAccelTimestampNs = timestampNs

        val fast = accelMagnitude >= FAST_ACCEL_THRESHOLD || latestGyroMagnitude >= FAST_GYRO_THRESHOLD
        val subtle = accelMagnitude >= SUBTLE_ACCEL_THRESHOLD || latestGyroMagnitude >= SUBTLE_GYRO_THRESHOLD
        val now = SystemClock.elapsedRealtime()

        fastEvidenceMs = if (fast) min(300f, fastEvidenceMs + dtMs) else max(0f, fastEvidenceMs - dtMs * 1.8f)
        subtleEvidenceMs = if (subtle) min(500f, subtleEvidenceMs + dtMs) else max(0f, subtleEvidenceMs - dtMs * 1.3f)

        if (subtle) lastSubtleActivityElapsedMs = now

        val trigger = accelMagnitude >= STRONG_ACCEL_THRESHOLD ||
            fastEvidenceMs >= FAST_EVIDENCE_MS ||
            subtleEvidenceMs >= SUBTLE_EVIDENCE_MS

        if (!inMotion && trigger) {
            inMotion = true
            RuntimeState.motionActive = true
            RuntimeState.recordMovement(max(accelMagnitude, latestGyroMagnitude * 0.5f))
        }

        if (inMotion && now - lastSubtleActivityElapsedMs >= MOTION_RELEASE_MS) {
            inMotion = false
            RuntimeState.motionActive = false
            fastEvidenceMs = 0f
            subtleEvidenceMs = 0f
        }
    }

    private fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Motion server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the LAN motion API and motion sensors active"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ip = NetworkUtil.lanIpv4(this) ?: "no LAN IP"
        val battery = if (RuntimeState.batteryPercent >= 0) "${RuntimeState.batteryPercent}%" else "?%"

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Motion server active")
            .setContentText("$ip:${RuntimeState.PORT} • battery $battery")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification() {
        if (!RuntimeState.running) return
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }
}
