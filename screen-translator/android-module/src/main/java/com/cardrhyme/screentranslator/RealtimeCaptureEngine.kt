package com.cardrhyme.screentranslator

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

internal data class OcrCapture(
    val bitmap: Bitmap,
    val motionX: Float,
    val motionY: Float,
)

internal class RealtimeCaptureEngine(
    private val context: Context,
    private val resultCode: Int,
    private val resultData: Intent,
    private val onMotion: (Float, Float) -> Unit,
    private val onCapture: (OcrCapture) -> Unit,
    private val onFps: (Int) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val tracker = ScreenMotionTracker()
    private val requestCapture = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var totalX = 0f
    private var totalY = 0f
    private var frames = 0
    private var fpsStart = 0L

    val width: Int get() = context.resources.displayMetrics.widthPixels
    val height: Int get() = context.resources.displayMetrics.heightPixels

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = onStopped()
    }

    fun start() {
        val metrics = context.resources.displayMetrics
        fpsStart = SystemClock.elapsedRealtime()
        thread = HandlerThread("screen-tracking", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        val handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).also {
            it.setOnImageAvailableListener(::onImage, handler)
        }
        projection = context.getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData).also { it.registerCallback(callback, handler) }
        display = projection?.createVirtualDisplay(
            "Realtime OCR capture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler,
        )
        check(display != null) { "virtual display failed" }
    }

    fun requestOcrCapture() {
        requestCapture.set(true)
    }

    private fun onImage(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage() ?: return
        try {
            tracker.update(image)?.let {
                if (it.confidence >= 0.04f && kotlin.math.abs(it.dxPixels) < 180f && kotlin.math.abs(it.dyPixels) < 180f) {
                    totalX += it.dxPixels
                    totalY += it.dyPixels
                    onMotion(it.dxPixels, it.dyPixels)
                }
            }
            reportFps()
            if (requestCapture.compareAndSet(true, false)) {
                onCapture(OcrCapture(image.toBitmap(), totalX, totalY))
            }
        } finally {
            image.close()
        }
    }

    private fun reportFps() {
        frames++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - fpsStart
        if (elapsed < 1000L) return
        onFps((frames * 1000f / elapsed).toInt())
        frames = 0
        fpsStart = now
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    fun close() {
        requestCapture.set(false)
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        projection?.unregisterCallback(callback)
        projection?.stop()
        thread?.quitSafely()
        display = null
        reader = null
        projection = null
        thread = null
    }
}
