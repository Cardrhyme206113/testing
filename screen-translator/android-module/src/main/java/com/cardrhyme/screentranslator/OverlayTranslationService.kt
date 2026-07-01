package com.cardrhyme.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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

private data class FrameOverlayResult(
    val items: List<OverlayItem>,
    val detectedCount: Int,
    val japaneseCount: Int,
)

private data class RecognizedRegion(
    val result: OCRResult,
    val text: String,
    val bounds: RectF,
)

class OverlayTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameRequested = AtomicBoolean(false)
    private val startingCapture = AtomicBoolean(false)
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
            scope.launch(Dispatchers.Main.immediate) {
                overlayView?.setError("screen capture stopped")
                delay(ERROR_VISIBLE_MS)
                stopSelf()
            }
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
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PREPARE -> {
                startBasicForeground("Overlay ready; choose screen capture")
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        createOverlay()
                        overlayView?.setStatus("OVERLAY OK • choose full screen")
                    } catch (error: Throwable) {
                        updateNotification("Overlay blocked: ${error.message ?: "unknown error"}")
                    }
                }
                return START_STICKY
            }

            ACTION_START -> Unit
            else -> return START_NOT_STICKY
        }

        if (mediaProjection != null || !startingCapture.compareAndSet(false, true)) {
            return START_STICKY
        }

        startProjectionForeground("Preparing OCR and translation…")
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent.intentExtra(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            startingCapture.set(false)
            scope.launch(Dispatchers.Main.immediate) {
                createOverlay()
                overlayView?.setError("missing screen-capture permission")
            }
            return START_STICKY
        }

        scope.launch {
            try {
                withContext(Dispatchers.Main.immediate) {
                    createOverlay()
                    overlayView?.setStatus("OVERLAY OK • loading OCR")
                }
                initializeModels()
                withContext(Dispatchers.Main.immediate) {
                    overlayView?.setStatus("OVERLAY OK • starting capture")
                }
                updateNotification("Scanning text on screen")
                startProjection(resultCode, resultData)
                captureLoop()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                startingCapture.set(false)
                val message = error.message?.take(80) ?: error.javaClass.simpleName
                updateNotification("Translator error: $message")
                withContext(Dispatchers.Main.immediate) {
                    runCatching { createOverlay() }
                    overlayView?.setError(message)
                }
                delay(ERROR_VISIBLE_MS)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun initializeModels() = coroutineScope {
        if (ocr != null) return@coroutineScope

        val ocrDeferred = async(Dispatchers.IO) {
            check(OpenCVUtils.init(this@OverlayTranslationService)) {
                "OpenCV could not initialize"
            }
            PaddleOCR.create(
                context = this@OverlayTranslationService,
                config = PaddleOCRConfig(
                    detMaxSideLimit = OCR_LONG_SIDE,
                    recScoreThresh = OCR_CONFIDENCE,
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
        check(virtualDisplay != null) { "virtual display could not start" }
    }

    private suspend fun captureLoop() {
        while (currentCoroutineContext().isActive) {
            withContext(Dispatchers.Main.immediate) {
                overlayView?.setCapturing(true, "CAPTURE • waiting for frame")
            }
            delay(OVERLAY_HIDE_MS)

            frameRequested.set(true)
            val bitmap = withTimeoutOrNull(FRAME_TIMEOUT_MS) { frameChannel.receive() }
            if (bitmap == null) {
                withContext(Dispatchers.Main.immediate) {
                    overlayView?.setResult("NO FRAME • capture blocked", emptyList())
                }
                delay(SCAN_PAUSE_MS)
                continue
            }

            withContext(Dispatchers.Main.immediate) {
                overlayView?.setCapturing(true, "OCR • processing")
            }
            val frameResult = try {
                processFrame(bitmap)
            } finally {
                bitmap.recycle()
            }
            withContext(Dispatchers.Main.immediate) {
                overlayView?.setResult(
                    "OCR ${frameResult.detectedCount} • JP ${frameResult.japaneseCount}",
                    frameResult.items,
                )
            }
            delay(SCAN_PAUSE_MS)
        }
    }

    private suspend fun processFrame(bitmap: Bitmap): FrameOverlayResult {
        val engine = ocr ?: return FrameOverlayResult(emptyList(), 0, 0)
        val recognized = engine.recognize(bitmap).results
            .asSequence()
            .filter { it.confidence >= OCR_CONFIDENCE }
            .map { result ->
                RecognizedRegion(
                    result = result,
                    text = normalizeText(result.text),
                    bounds = result.toScreenBounds(bitmap),
                )
            }
            .filter { region -> region.text.isNotBlank() && !isStatusPillRegion(region.bounds) }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(MAX_LINES)
            .toList()

        val japaneseCount = recognized.count { region -> containsJapanese(region.text) }
        val items = coroutineScope {
            recognized.map { region ->
                async {
                    if (!containsJapanese(region.text)) {
                        OverlayItem(bounds = region.bounds, kind = OverlayKind.HIGHLIGHT)
                    } else {
                        val english = runCatching { translateCached(region.text) }.getOrDefault("")
                        if (english.isBlank() || english.equals(region.text, ignoreCase = true)) {
                            OverlayItem(bounds = region.bounds, kind = OverlayKind.HIGHLIGHT)
                        } else {
                            OverlayItem(
                                bounds = region.bounds,
                                text = english,
                                kind = OverlayKind.TRANSLATED,
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        return FrameOverlayResult(
            items = items,
            detectedCount = recognized.size,
            japaneseCount = japaneseCount,
        )
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

    private fun OCRResult.toScreenBounds(bitmap: Bitmap): RectF {
        val scaleX = captureWidth.toFloat() / bitmap.width
        val scaleY = captureHeight.toFloat() / bitmap.height
        return RectF(
            box.points.minOf { it.x } * scaleX,
            box.points.minOf { it.y } * scaleY,
            box.points.maxOf { it.x } * scaleX,
            box.points.maxOf { it.y } * scaleY,
        )
    }

    private fun isStatusPillRegion(bounds: RectF): Boolean {
        val density = resources.displayMetrics.density
        return bounds.left < 380f * density && bounds.top < 72f * density
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

    private fun startBasicForeground(text: String) {
        startForeground(NOTIFICATION_ID, notification(text))
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
        const val ACTION_PREPARE = "com.cardrhyme.screentranslator.PREPARE"
        const val ACTION_START = "com.cardrhyme.screentranslator.START"
        const val ACTION_STOP = "com.cardrhyme.screentranslator.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "screen_translation"
        private const val NOTIFICATION_ID = 42
        private const val OCR_LONG_SIDE = 1920
        private const val OCR_CONFIDENCE = 0.25f
        private const val MAX_LINES = 48
        private const val OVERLAY_HIDE_MS = 120L
        private const val FRAME_TIMEOUT_MS = 1500L
        private const val SCAN_PAUSE_MS = 800L
        private const val ERROR_VISIBLE_MS = 8000L
    }
}
