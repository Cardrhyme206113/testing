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
        const val EXTRA_FADE_LEVELS = "fade_levels"
        const val EXTRA_COMMAND_GAP = "command_gap"
        const val EXTRA_COLOR_DELAY = "color_delay"
        private const val CHANNEL_ID = "ir_music_capture"
        private const val NOTIFICATION_ID = 4201

        private const val BRIGHTNESS_DOWN = 0xF7807FL
        private const val BRIGHTNESS_UP = 0xF700FFL
        private const val POWER_OFF = 0xF740BFL
        private const val POWER_ON = 0xF7C03FL
    }

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var animating = false
    private var sensitivity = 55
    private var intervalProgress = 0
    private var fadeLevels = 2
    private var commandGapMs = 12L
    private var colorDelayMs = 75L
    private var lastBeatAt = 0L
    private var previousEnergy = DoubleArray(8)
    private var previousBand = -1
    private lateinit var ir: ConsumerIrManager

    private val colorCodes = longArrayOf(
        0xF720DFL,
        0xF710EFL,
        0xF730CFL,
        0xF708F7L,
        0xF728D7L,
        0xF7A05FL,
        0xF748B7L,
        0xF76897L,
        0xF7E01FL
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
        if (resultCode != Activity.RESULT_OK || resultData == null) { stopSelf(); return }

        sensitivity = intent.getIntExtra(EXTRA_SENSITIVITY, 55).coerceIn(0, 100)
        intervalProgress = intent.getIntExtra(EXTRA_INTERVAL, 0).coerceIn(0, 800)
        fadeLevels = intent.getIntExtra(EXTRA_FADE_LEVELS, 2).coerceIn(0, 6)
        commandGapMs = intent.getIntExtra(EXTRA_COMMAND_GAP, 12).coerceIn(5, 50).toLong()
        colorDelayMs = intent.getIntExtra(EXTRA_COLOR_DELAY, 75).coerceIn(25, 300).toLong()

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Starting playback capture…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData).also {
                it.registerCallback(object : MediaProjection.Callback() {
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
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(8192, minimum * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        recorder?.startRecording()
        running = true
        updateNotification("${fadeLevels}-step fade · ${commandGapMs} ms gap · ${200 + intervalProgress} ms cooldown")

        worker = thread(name = "ir-playback-analyzer") {
            val pcm = ShortArray(2048)
            while (running) {
                val count = recorder?.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) ?: break
                if (count > 512) analyze(pcm, count, sampleRate)
            }
        }
    }

    private fun analyze(samples: ShortArray, count: Int, sampleRate: Int) {
        val frequencies = doubleArrayOf(60.0, 110.0, 220.0, 440.0, 900.0, 1800.0, 3600.0, 7200.0)
        val energies = DoubleArray(frequencies.size)
        val limit = min(count, 1024)

        var rmsSum = 0.0
        for (i in 0 until limit) {
            val v = samples[i] / 32768.0
            rmsSum += v * v
        }
        val rms = sqrt(rmsSum / limit)
        val silenceThreshold = (0.040 - sensitivity * 0.00034).coerceAtLeast(0.004)
        if (rms < silenceThreshold) return

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

        val total = energies.sum().coerceAtLeast(1.0)
        val normalized = DoubleArray(energies.size) { energies[it] / total }
        var flux = 0.0
        for (i in normalized.indices) flux += max(0.0, normalized[i] - previousEnergy[i])
        val dominantBand = normalized.indices.maxByOrNull { normalized[it] } ?: return
        val bandChanged = previousBand >= 0 && abs(dominantBand - previousBand) >= 1
        val fluxThreshold = 0.24 - sensitivity * 0.0015
        val actualChange = bandChanged && flux >= fluxThreshold.coerceAtLeast(0.07)

        previousEnergy = normalized
        previousBand = dominantBand
        if (!actualChange || animating) return

        val now = System.currentTimeMillis()
        val cooldownMs = 200L + intervalProgress
        if (now - lastBeatAt < cooldownMs) return
        lastBeatAt = now

        val centroid = frequencies.indices.sumOf { frequencies[it] * normalized[it] }
        val position = (ln(centroid / 55.0) / ln(8000.0 / 55.0)).coerceIn(0.0, 0.999)
        val colorIndex = (position * colorCodes.size).toInt().coerceIn(0, colorCodes.lastIndex)
        animateBeat(colorCodes[colorIndex], cooldownMs)
    }

    private fun animateBeat(colorCode: Long, cooldownMs: Long) {
        animating = true
        thread(name = "ir-fade-animation") {
            try {
                repeat(fadeLevels) {
                    sendCode(BRIGHTNESS_DOWN)
                    Thread.sleep(commandGapMs)
                }
                sendCode(POWER_OFF)

                val elapsed = System.currentTimeMillis() - lastBeatAt
                if (elapsed < cooldownMs) Thread.sleep(cooldownMs - elapsed)

                sendCode(POWER_ON)
                Thread.sleep(commandGapMs)
                repeat(fadeLevels) {
                    sendCode(BRIGHTNESS_UP)
                    Thread.sleep(commandGapMs)
                }

                Thread.sleep(colorDelayMs)
                sendCode(colorCode)
            } catch (_: InterruptedException) {
            } finally {
                animating = false
            }
        }
    }

    private fun sendCode(code: Long) {
        if (!running || !ir.hasIrEmitter()) return
        val prefs = getSharedPreferences("ir_profile", MODE_PRIVATE)
        val carrier = prefs.getInt("carrier", 38_000)
        val mode = prefs.getInt("mode", 0)
        val repeats = prefs.getInt("repeats", 1).coerceIn(1, 3)
        try {
            repeat(repeats) {
                ir.transmit(carrier, IrProtocol.necPattern(code, mode))
                if (repeats > 1) Thread.sleep(35)
            }
        } catch (_: Throwable) {}
    }

    private fun stopEverything() {
        running = false
        worker?.interrupt()
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder?.release(); recorder = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder?.release(); recorder = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
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
