package dev.cardrhyme.irmusicsync

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.ConsumerIrManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import kotlin.concurrent.thread
import kotlin.math.*

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "dev.cardrhyme.irmusicsync.START"
        const val ACTION_STOP = "dev.cardrhyme.irmusicsync.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SENSITIVITY = "sensitivity"
        const val EXTRA_INTERVAL = "interval"
        private const val CHANNEL_ID = "ir_music_capture"
        private const val NOTIFICATION_ID = 4201
    }

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var lastColor = -1
    private var lastSend = 0L
    private var sensitivity = 45
    private var intervalProgress = 100
    private lateinit var ir: ConsumerIrManager

    private val codes = longArrayOf(
        0x00F720DFL, 0x00F710EFL, 0x00F730CFL, 0x00F708F7L,
        0x00F728D7L, 0x00F7A05FL, 0x00F7906FL, 0x00F7B04FL,
        0x00F78877L, 0x00F7A857L, 0x00F7609FL, 0x00F750AFL,
        0x00F7708FL, 0x00F748B7L, 0x00F76897L, 0x00F7E01FL
    )

    override fun onCreate() {
        super.onCreate()
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            ACTION_START -> startFromPermission(intent)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startFromPermission(intent: Intent) {
        if (running) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return
        }
        sensitivity = intent.getIntExtra(EXTRA_SENSITIVITY, 45).coerceIn(0, 100)
        intervalProgress = intent.getIntExtra(EXTRA_INTERVAL, 100).coerceIn(0, 420)

        val notification = buildNotification("Starting playback capture…")
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData).also { mediaProjection ->
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = stopEverything()
                }, null)
            }
            startAudioRecord()
        } catch (t: Throwable) {
            updateNotification("Capture failed: ${t.message ?: t.javaClass.simpleName}")
            stopEverything()
        }
    }

    private fun startAudioRecord() {
        val mediaProjection = projection ?: return
        val sampleRate = 44_100
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(8192, minimum * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        recorder?.startRecording()
        running = true
        updateNotification("Listening to device playback audio")

        worker = thread(name = "ir-playback-analyzer") {
            val pcm = ShortArray(2048)
            while (running) {
                val count = recorder?.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) ?: break
                if (count > 256) analyze(pcm, count, sampleRate)
            }
        }
    }

    private fun analyze(samples: ShortArray, count: Int, sampleRate: Int) {
        var squareSum = 0.0
        for (i in 0 until count) {
            val value = samples[i] / 32768.0
            squareSum += value * value
        }
        val rms = sqrt(squareSum / count)
        val threshold = (0.055 - sensitivity * 0.00048).coerceAtLeast(0.003)
        if (rms < threshold) return

        val frequencies = doubleArrayOf(
            45.0, 70.0, 105.0, 160.0, 240.0, 360.0, 540.0, 800.0,
            1200.0, 1800.0, 2700.0, 4000.0, 6000.0, 8500.0, 12000.0, 16000.0
        )
        val energies = DoubleArray(frequencies.size)
        val limit = min(count, 1024)
        for (band in frequencies.indices) {
            val omega = 2.0 * Math.PI * frequencies[band] / sampleRate
            var real = 0.0
            var imaginary = 0.0
            for (i in 0 until limit) {
                val window = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (limit - 1))
                val sample = samples[i] * window
                real += sample * cos(omega * i)
                imaginary -= sample * sin(omega * i)
            }
            energies[band] = real * real + imaginary * imaginary
        }

        val total = energies.sum()
        if (total <= 0.0) return
        var weighted = 0.0
        var peak = 0.0
        for (i in energies.indices) {
            weighted += energies[i] * frequencies[i]
            peak = max(peak, energies[i])
        }
        val centroid = (weighted / total).coerceIn(35.0, 16_000.0)
        val thinness = (peak / (total / energies.size)).coerceIn(1.0, 16.0)
        val logPosition = (ln(centroid / 35.0) / ln(16_000.0 / 35.0)).coerceIn(0.0, 1.0)
        val shifted = (logPosition + (thinness - 4.0) * 0.018).coerceIn(0.0, 0.999)
        var index = (shifted * codes.size).toInt()
        if (lastColor >= 0 && abs(index - lastColor) == 1 && thinness < 6.0) index = lastColor

        val now = System.currentTimeMillis()
        if (index != lastColor && now - lastSend >= 80L + intervalProgress) {
            sendCode(codes[index])
            lastColor = index
            lastSend = now
        }
    }

    private fun sendCode(code: Long) {
        if (!ir.hasIrEmitter()) return
        val prefs = getSharedPreferences("ir_profile", MODE_PRIVATE)
        val carrier = prefs.getInt("carrier", 38_000)
        val mode = prefs.getInt("mode", 0)
        val repeats = prefs.getInt("repeats", 1).coerceIn(1, 3)
        try {
            repeat(repeats) {
                ir.transmit(carrier, IrProtocol.necPattern(code, mode))
                if (repeats > 1) Thread.sleep(35)
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopEverything() {
        if (!running && projection == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        running = false
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder?.release()
        recorder = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder?.release()
        recorder = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "IR music capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(message: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("IR Music Sync")
        .setContentText(message)
        .setOngoing(true)
        .build()

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }
}
