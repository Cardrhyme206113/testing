package com.cardrhyme.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class OverlayTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captures = Channel<OcrCapture>(Channel.CONFLATED)
    private val motionLock = Any()

    private var captureEngine: RealtimeCaptureEngine? = null
    private var overlay: FastOverlayView? = null
    private var ocr: RealtimeOcrController? = null
    private var totalMotionX = 0f
    private var totalMotionY = 0f
    private var resultGeneration = 0L
    private var lastStatus = "Starting"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || captureEngine != null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent.intentExtra(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) return START_NOT_STICKY

        startProjectionForeground("Preparing Tiny full-resolution OCR")
        scope.launch {
            try {
                withContext(Dispatchers.Main.immediate) { createOverlay() }
                ocr = RealtimeOcrController(this@OverlayTranslationService) { message ->
                    overlay?.post { overlay?.setStatus(message) }
                }.also { it.initialize() }

                captureEngine = RealtimeCaptureEngine(
                    context = this@OverlayTranslationService,
                    resultCode = resultCode,
                    resultData = resultData,
                    onMotion = { dx, dy ->
                        synchronized(motionLock) {
                            totalMotionX += dx
                            totalMotionY += dy
                        }
                        overlay?.post { overlay?.moveBy(dx, dy) }
                    },
                    onCapture = { captures.trySend(it) },
                    onFps = { fps ->
                        overlay?.post { overlay?.setStatus("$lastStatus • $fps fps track") }
                    },
                    onStopped = { stopSelf() },
                ).also { it.start() }
                runOcrLoop()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = error.message?.take(100) ?: error.javaClass.simpleName
                withContext(Dispatchers.Main.immediate) {
                    runCatching { createOverlay() }
                    overlay?.setError(message)
                }
                updateNotification("Error: $message")
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runOcrLoop() {
        while (currentCoroutineContext().isActive) {
            withContext(Dispatchers.Main.immediate) { overlay?.beginCapture("Clean full-res frame") }
            delay(20)
            captureEngine?.requestOcrCapture()
            val frame = withTimeoutOrNull(650) { captures.receive() }
            if (frame == null) {
                withContext(Dispatchers.Main.immediate) { overlay?.endCapture("Tracking • no OCR frame") }
                delay(120)
                continue
            }

            withContext(Dispatchers.Main.immediate) { overlay?.endCapture("Tracking • Tiny OCR") }
            val output = try {
                ocr?.process(
                    frame.bitmap,
                    captureEngine?.width ?: frame.bitmap.width,
                    captureEngine?.height ?: frame.bitmap.height,
                )
            } finally {
                frame.bitmap.recycle()
            } ?: continue

            val currentMotion = synchronized(motionLock) { totalMotionX to totalMotionY }
            val correctionX = currentMotion.first - frame.motionX
            val correctionY = currentMotion.second - frame.motionY
            val movedItems = output.items.map { it.shifted(correctionX, correctionY) }
            val movedPending = output.pendingTranslations.map { work ->
                work.copy(item = work.item.shifted(correctionX, correctionY))
            }
            val generation = ++resultGeneration

            lastStatus = "${output.modelLabel} • OCR ${output.detectedCount} • JP ${output.japaneseCount} • ${output.elapsedMs}ms"
            withContext(Dispatchers.Main.immediate) {
                if (movedItems.isNotEmpty()) overlay?.setResult(lastStatus, movedItems)
                else overlay?.setStatus(lastStatus)
            }

            if (movedPending.isNotEmpty()) {
                scope.launch {
                    val translated = ocr?.translatePending(movedPending).orEmpty()
                    if (translated.isNotEmpty() && generation == resultGeneration) {
                        withContext(Dispatchers.Main.immediate) {
                            overlay?.mergeTranslations(translated)
                        }
                    }
                }
            }
            delay(80)
        }
    }

    private fun OverlayItem.shifted(dx: Float, dy: Float): OverlayItem {
        val moved = RectF(bounds)
        moved.offset(dx, dy)
        return copy(bounds = moved)
    }

    private fun createOverlay() {
        if (overlay != null) return
        val view = FastOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
    }

    private fun startProjectionForeground(text: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen translation", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stopIntent = Intent(this, OverlayTranslationService::class.java).apply { action = ACTION_STOP }
        val stopPending = android.app.PendingIntent.getService(
            this,
            0,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Japanese Screen Translator")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    override fun onDestroy() {
        captureEngine?.close()
        captures.close()
        runBlocking { ocr?.close() }
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun Intent.intentExtra(name: String): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(name, Intent::class.java)
    } else {
        getParcelableExtra(name)
    }

    companion object {
        const val ACTION_START = "com.cardrhyme.screentranslator.START"
        const val ACTION_STOP = "com.cardrhyme.screentranslator.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_translation"
        private const val NOTIFICATION_ID = 42
    }
}
