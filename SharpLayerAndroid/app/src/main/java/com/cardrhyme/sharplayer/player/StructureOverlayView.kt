package com.cardrhyme.sharplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import com.cardrhyme.sharplayer.codec.StructureCodec

/** Draws the 10 Hz structural sidecar over the ordinary low-bitrate MP4. */
class StructureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var sequence: StructureCodec.Sequence? = null
    private var decoder: StructureCodec.PlaybackDecoder? = null
    private var player: ExoPlayer? = null
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var lastBucket = Long.MIN_VALUE
    private var opacity = 0.72f

    private val ticker = object : Runnable {
        override fun run() {
            updateFromPlayer()
            if (isAttachedToWindow) postDelayed(this, 33L)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(newPlayer: ExoPlayer?, newSequence: StructureCodec.Sequence?) {
        player = newPlayer
        if (sequence !== newSequence) {
            sequence = newSequence
            decoder = newSequence?.let { StructureCodec.PlaybackDecoder(it) }
            bitmap?.recycle()
            bitmap = newSequence?.let {
                Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
            }
            pixels = newSequence?.let { IntArray(it.width * it.height) } ?: IntArray(0)
            lastBucket = Long.MIN_VALUE
        }
        updateFromPlayer(force = true)
    }

    fun setLineOpacity(value: Float) {
        opacity = value.coerceIn(0f, 1f)
        lastBucket = Long.MIN_VALUE
        updateFromPlayer(force = true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentBitmap = bitmap ?: return
        val currentSequence = sequence ?: return
        if (width <= 0 || height <= 0) return

        val videoAspect = currentSequence.width.toFloat() / currentSequence.height.toFloat()
        val viewAspect = width.toFloat() / height.toFloat()
        val destination = if (viewAspect > videoAspect) {
            val drawWidth = height * videoAspect
            val left = (width - drawWidth) / 2f
            RectF(left, 0f, left + drawWidth, height.toFloat())
        } else {
            val drawHeight = width / videoAspect
            val top = (height - drawHeight) / 2f
            RectF(0f, top, width.toFloat(), top + drawHeight)
        }
        canvas.drawBitmap(currentBitmap, null, destination, paint)
    }

    private fun updateFromPlayer(force: Boolean = false) {
        val activePlayer = player ?: return
        val activeSequence = sequence ?: return
        val activeDecoder = decoder ?: return
        val activeBitmap = bitmap ?: return
        val position = activePlayer.currentPosition.coerceAtLeast(0L)
        val bucket = position / 100L
        if (!force && bucket == lastBucket) return
        lastBucket = bucket

        val state = activeDecoder.stateAt(position)
        val darkAlpha = (190 * opacity).toInt().coerceIn(0, 255)
        val lightAlpha = (155 * opacity).toInt().coerceIn(0, 255)
        val geometryAlpha = (235 * opacity).toInt().coerceIn(0, 255)
        for (i in state.indices) {
            pixels[i] = when (state[i].toInt()) {
                1 -> Color.argb(darkAlpha, 0, 0, 0)
                2 -> Color.argb(lightAlpha, 255, 255, 255)
                3 -> Color.argb(geometryAlpha, 5, 8, 12)
                else -> Color.TRANSPARENT
            }
        }
        activeBitmap.setPixels(
            pixels,
            0,
            activeSequence.width,
            0,
            0,
            activeSequence.width,
            activeSequence.height
        )
        invalidate()
    }
}
