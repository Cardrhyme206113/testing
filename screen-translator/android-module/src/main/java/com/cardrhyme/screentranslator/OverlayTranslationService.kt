package com.cardrhyme.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

class OverlayTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameRequested = AtomicBoolean(false)
    private val frameChannel = Channel<Bitmap>(Channel.CONFLATED)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var overlayView: TranslationOverlayView? = null
    private var ocr: PaddleOCR? = null
    private lateinit var translator: Translator

    private var captureWidth = 0
    private var captureHeight = 0
    private var densityDpi = 0

    private val translationCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 256
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || mediaProjection != null) {
            return START_NOT_STICKY
        }

        startProjectionForeground("Preparing PP-OCRv6 and translation models…")
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent.intentExtra(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                withContext(Dispatchers.Main.immediate) { createOverlay() }
                initializeModels()
                updateNotification("Translating Japanese text on screen")
                startProjection(resultCode, resultData)
                captureLoop()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateNotification("Stopped: ${error.message ?: "initialization error"}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun initializeModels() = coroutineScope {
        val ocrDeferred = async(Dispatchers.IO) {
            check(OpenCVUtils.init(this@OverlayTranslationService)) {
                "OpenCV could not initialize"
            }
            PaddleOCR.create(
                context = this@OverlayTranslationService,
                config = PaddleOCRConfig(
                    detMaxSideLimit = OCR_LONG_SIDE,
                    recScoreThresh = 0.45f,
                    recBatchSize = 4,
                ),
                engineConfig = EngineConfig(
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                ),
                detModelAssetPath = "models/det/inference.onnx",
                recModelAssetPath = "models/rec/inference.onnx",
                recConfigAssetPath = "models/rec/inference.yml",
            )
        }
        val translationDeferred = async { downloadTranslationModel() }
        ocr = ocrDeferred.await()
        translationDeferred.await()
    }

    private suspend fun downloadTranslationModel() {
        suspendCancellableCoroutine<Unit> { continuation ->
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val metrics = resources.displayMetrics
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi

        captureThread = HandlerThread("screen-capture").also { it.start() }
        val captureHandler = Handler(captureThread!!.looper)
        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            3,
        ).also { reader ->
            reader.setOnImageAvailableListener(::onImageAvailable, captureHandler)
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = manager.getMediaProjection(resultCode, data).also { projection ->
            projection.registerCallback(projectionCallback, captureHandler)
        }
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PP-OCRv6 screen capture",
            captureWidth,
            captureHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler,
        )
    }

    private suspend fun captureLoop() {
        while (currentCoroutineContext().isActive) {
            withContext(Dispatchers.Main.immediate) {
                overlayView?.visibility = View.INVISIBLE
            }
            delay(OVERLAY_HIDE_MS)

            frameRequested.set(true)
            val bitmap = withTimeoutOrNull(FRAME_TIMEOUT_MS) { frameChannel.receive() }
            if (bitmap == null) {
                withContext(Dispatchers.Main.immediate) {
                    overlayView?.visibility = View.VISIBLE
                }
                delay(SCAN_PAUSE_MS)
                continue
            }

            val items = try {
                processFrame(bitmap)
            } finally {
                bitmap.recycle()
            }
            withContext(Dispatchers.Main.immediate) {
                overlayView?.setItems(items)
                overlayView?.visibility = View.VISIBLE
            }
            delay(SCAN_PAUSE_MS)
        }
    }

    private suspend fun processFrame(bitmap: Bitmap): List<OverlayItem> {
        val engine = ocr ?: return emptyList()
        val results = engine.recognize(bitmap).results
            .asSequence()
            .filter { it.confidence >= 0.45f }
            .map { it to normalizeText(it.text) }
            .filter { (_, text) -> text.length >= 2 && containsJapanese(text) }
            .sortedWith(compareBy({ pair -> pair.first.box.points.minOf { it.y } }, { pair -> pair.first.box.points.minOf { it.x } }))
            .take(MAX_LINES)
            .toList()

        return coroutineScope {
            results.map { (result, text) ->
                async {
                    val english = translateCached(text)
                    if (english.isBlank() || english.equals(text, ignoreCase = true)) {
                        null
                    } else {
                        result.toOverlayItem(bitmap, english)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun translateCached(text: String): String {
        synchronized(translationCache) {
            translationCache[text]?.let { return it }
            translationCache.entries.firstOrNull { isNearDuplicate(it.key, text) }
                ?.value
                ?.let { return it }
        }

        val translated = suspendCancellableCoroutine<String> { continuation ->
            translator.translate(text)
                .addOnSuccessListener { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
        synchronized(translationCache) {
            translationCache[text] = translated
        }
        return translated
    }

    private fun OCRResult.toOverlayItem(bitmap: Bitmap, english: String): OverlayItem {
        val points = box.points
        val scaleX = captureWidth.toFloat() / bitmap.width
        val scaleY = captureHeight.toFloat() / bitmap.height
        return OverlayItem(
            bounds = RectF(
                points.minOf { it.x } * scaleX,
                points.minOf { it.y } * scaleY,
                points.maxOf { it.x } * scaleX,
                points.maxOf { it.y } * scaleY,
            ),
            text = english,
        )
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (!frameRequested.compareAndSet(true, false)) return
            val original = image.toBitmap()
            val scaled = scaleForOcr(original)
            if (scaled !== original) original.recycle()
            if (frameChannel.trySend(scaled).isFailure) scaled.recycle()
        } finally {
            image.close()
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun scaleForOcr(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= OCR_LONG_SIDE) return bitmap
        val ratio = OCR_LONG_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * ratio).toInt()),
            max(1, (bitmap.height * ratio).toInt()),
            true,
        )
    }

    private fun createOverlay() {
        if (overlayView != null) return
        val windowManager = getSystemService(WindowManager::class.java)
        overlayView = TranslationOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(overlayView, params)
    }

    private fun normalizeText(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsJapanese(value: String): Boolean {
        return value.any { character ->
            character.code in 0x3040..0x30FF ||
                character.code in 0x3400..0x9FFF ||
                character.code in 0xF900..0xFAFF
        }
    }

    private fun isNearDuplicate(first: String, second: String): Boolean {
        if (first == second) return true
        val a = first.replace(" ", "")
        val b = second.replace(" ", "")
        val longest = max(a.length, b.length)
        if (min(a.length, b.length) < 6 || kotlin.math.abs(a.length - b.length) > max(1, longest / 12)) {
            return false
        }
        return levenshteinDistance(a, b, max(1, longest / 12)) <= max(1, longest / 12)
    }

    private fun levenshteinDistance(first: String, second: String, stopAfter: Int): Int {
        var previous = IntArray(second.length + 1) { it }
        for (i in first.indices) {
            val current = IntArray(second.length + 1)
            current[0] = i + 1
            var rowMinimum = current[0]
            for (j in second.indices) {
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    previous[j] + if (first[i] == second[j]) 0 else 1,
                )
                rowMinimum = min(rowMinimum, current[j + 1])
            }
            if (rowMinimum > stopAfter) return rowMinimum
            previous = current
        }
        return previous[second.length]
    }

    private fun startProjectionForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stopIntent = Intent(this, OverlayTranslationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
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
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen translation",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onDestroy() {
        frameRequested.set(false)
        frameChannel.close()
        virtualDisplay?.release()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        captureThread?.quitSafely()
        translator.close()
        runBlocking(Dispatchers.IO) { ocr?.release() }
        overlayView?.let { view ->
            runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun Intent.intentExtra(name: String): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(name, Intent::class.java)
        } else {
            getParcelableExtra(name)
        }
    }

    companion object {
        const val ACTION_START = "com.cardrhyme.screentranslator.START"
        const val ACTION_STOP = "com.cardrhyme.screentranslator.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "screen_translation"
        private const val NOTIFICATION_ID = 42
        private const val OCR_LONG_SIDE = 1920
        private const val MAX_LINES = 16
        private const val OVERLAY_HIDE_MS = 120L
        private const val FRAME_TIMEOUT_MS = 900L
        private const val SCAN_PAUSE_MS = 800L
    }
}
