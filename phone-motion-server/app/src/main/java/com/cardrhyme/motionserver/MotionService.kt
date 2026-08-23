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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.sqrt

class MotionService : Service(), SensorEventListener {
    companion object {
        private const val CHANNEL_ID = "motion_server"
        private const val NOTIFICATION_ID = 42061
        private const val SAMPLE_PERIOD_US = 20_000 // ~50 Hz

        // Event detector, not a latched "moving/still" state.
        // A movement can therefore be reported without ever waiting for a re-arm transition.
        private const val MIN_EVENT_INTERVAL_MS = 250L
        private const val ACCEL_MIN_THRESHOLD = 0.028f       // m/s^2 sample-to-sample change
        private const val GYRO_MIN_THRESHOLD = 0.025f        // rad/s (~1.4 deg/s)
        private const val ACCEL_IMMEDIATE = 0.060f
        private const val GYRO_IMMEDIATE = 0.050f
        private const val EVIDENCE_TRIGGER = 0.070f
    }

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var httpServer: LocalHttpServer? = null

    private val previousAccel = FloatArray(3)
    private var havePreviousAccel = false
    private var latestGyroMagnitude = 0f
    private var accelNoise = 0.004f
    private var gyroNoise = 0.003f
    private var evidence = 0f
    private var lastAccelTimestampNs = 0L
    private var lastMovementElapsedMs = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            RuntimeState.batteryPercent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeState.resetForServiceStart()

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
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        try { sensorThread?.quitSafely() } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { httpServer?.stop() } catch (_: Exception) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        sensorHandler = null
        sensorThread = null
        httpServer = null
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSensors() {
        sensorManager = getSystemService(SensorManager::class.java)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        sensorThread = HandlerThread("motion-sensors", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
        sensorHandler = Handler(sensorThread!!.looper)

        val accelOk = accelSensor?.let {
            sensorManager.registerListener(this, it, SAMPLE_PERIOD_US, 0, sensorHandler)
        } ?: false
        val gyroOk = gyroSensor?.let {
            sensorManager.registerListener(this, it, SAMPLE_PERIOD_US, 0, sensorHandler)
        } ?: false

        RuntimeState.accelRegistered = accelOk
        RuntimeState.gyroRegistered = gyroOk
        RuntimeState.accelSensorName = accelSensor?.name ?: "none"
        RuntimeState.gyroSensorName = gyroSensor?.name ?: "none"

        if (!accelOk) RuntimeState.lastError = "Accelerometer listener registration failed"
    }

    override fun onSensorChanged(event: SensorEvent) {
        RuntimeState.lastSensorEventElapsedMs = SystemClock.elapsedRealtime()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> processAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> processGyroscope(event)
        }
    }

    private fun processAccelerometer(event: SensorEvent) {
        RuntimeState.accelEvents += 1

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!havePreviousAccel) {
            previousAccel[0] = x
            previousAccel[1] = y
            previousAccel[2] = z
            havePreviousAccel = true
            lastAccelTimestampNs = event.timestamp
            return
        }

        val dx = x - previousAccel[0]
        val dy = y - previousAccel[1]
        val dz = z - previousAccel[2]
        previousAccel[0] = x
        previousAccel[1] = y
        previousAccel[2] = z

        val delta = magnitude(dx, dy, dz)
        val dtMs = ((event.timestamp - lastAccelTimestampNs) / 1_000_000f).coerceIn(5f, 80f)
        lastAccelTimestampNs = event.timestamp

        // Learn only the low-level floor. Actual movement should not inflate the threshold.
        if (delta < 0.050f) accelNoise = accelNoise * 0.985f + delta * 0.015f
        val accelThreshold = max(ACCEL_MIN_THRESHOLD, accelNoise * 5.0f)
        val gyroThreshold = max(GYRO_MIN_THRESHOLD, gyroNoise * 6.0f)

        // Rolling evidence catches a gentle start/stop spread across several samples.
        val decay = if (dtMs <= 25f) 0.82f else 0.70f
        evidence *= decay
        if (delta > accelThreshold) evidence += (delta - accelThreshold)
        if (latestGyroMagnitude > gyroThreshold) evidence += (latestGyroMagnitude - gyroThreshold) * 0.35f

        RuntimeState.accelDelta = delta
        RuntimeState.gyroMagnitude = latestGyroMagnitude
        RuntimeState.accelThreshold = accelThreshold
        RuntimeState.gyroThreshold = gyroThreshold
        RuntimeState.motionEvidence = evidence

        val trigger = delta >= max(ACCEL_IMMEDIATE, accelThreshold * 1.8f) ||
            latestGyroMagnitude >= max(GYRO_IMMEDIATE, gyroThreshold * 1.6f) ||
            evidence >= EVIDENCE_TRIGGER

        if (trigger) recordEvent(max(delta, latestGyroMagnitude))
    }

    private fun processGyroscope(event: SensorEvent) {
        RuntimeState.gyroEvents += 1
        val gyro = magnitude(event.values[0], event.values[1], event.values[2])
        latestGyroMagnitude = gyro

        if (gyro < 0.040f) gyroNoise = gyroNoise * 0.985f + gyro * 0.015f
        val gyroThreshold = max(GYRO_MIN_THRESHOLD, gyroNoise * 6.0f)
        RuntimeState.gyroMagnitude = gyro
        RuntimeState.gyroThreshold = gyroThreshold

        if (gyro >= max(GYRO_IMMEDIATE, gyroThreshold * 1.6f)) {
            recordEvent(gyro)
        }
    }

    private fun recordEvent(strength: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMovementElapsedMs < MIN_EVENT_INTERVAL_MS) return
        lastMovementElapsedMs = now
        RuntimeState.recordMovement(strength)
        evidence = 0f
        RuntimeState.motionEvidence = 0f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Motion server", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the LAN motion API and motion sensors active"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }
}
