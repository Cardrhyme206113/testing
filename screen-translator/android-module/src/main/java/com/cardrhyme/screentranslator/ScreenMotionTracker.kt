package com.cardrhyme.screentranslator

import android.media.Image
import kotlin.math.abs
import kotlin.math.max

class ScreenMotionTracker(
    private val targetWidth: Int = 120,
) {
    data class Motion(val dxPixels: Float, val dyPixels: Float, val confidence: Float)

    private data class Frame(
        val pixels: ByteArray,
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    private var previous: Frame? = null

    fun reset() {
        previous = null
    }

    fun update(image: Image): Motion? {
        val current = sampleFrame(image)
        val old = previous
        previous = current
        if (old == null || old.width != current.width || old.height != current.height) return null

        val estimate = estimateShift(old, current) ?: return null
        val scaleX = current.sourceWidth.toFloat() / current.width
        val scaleY = current.sourceHeight.toFloat() / current.height
        return Motion(
            dxPixels = estimate.first * scaleX,
            dyPixels = estimate.second * scaleY,
            confidence = estimate.third,
        )
    }

    private fun sampleFrame(image: Image): Frame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = targetWidth.coerceAtMost(image.width)
        val height = max(1, image.height * width / image.width)
        val pixels = ByteArray(width * height)

        for (y in 0 until height) {
            val sourceY = (y.toLong() * image.height / height).toInt().coerceAtMost(image.height - 1)
            val row = sourceY * rowStride
            for (x in 0 until width) {
                val sourceX = (x.toLong() * image.width / width).toInt().coerceAtMost(image.width - 1)
                val index = row + sourceX * pixelStride
                val r = buffer.get(index).toInt() and 0xFF
                val g = buffer.get(index + 1).toInt() and 0xFF
                val b = buffer.get(index + 2).toInt() and 0xFF
                pixels[y * width + x] = ((r * 54 + g * 183 + b * 19) shr 8).toByte()
            }
        }
        return Frame(pixels, width, height, image.width, image.height)
    }

    private fun estimateShift(previous: Frame, current: Frame): Triple<Int, Int, Float>? {
        val width = current.width
        val height = current.height
        if (width < 32 || height < 32) return null

        val zeroScore = score(previous, current, 0, 0)
        var bestDx = 0
        var bestDy = 0
        var bestScore = zeroScore
        var secondScore = Float.MAX_VALUE

        for (dy in -MAX_SHIFT..MAX_SHIFT step 2) {
            for (dx in -MAX_SHIFT..MAX_SHIFT step 2) {
                val value = score(previous, current, dx, dy)
                if (value < bestScore) {
                    secondScore = bestScore
                    bestScore = value
                    bestDx = dx
                    bestDy = dy
                } else if (value < secondScore) {
                    secondScore = value
                }
            }
        }

        val coarseX = bestDx
        val coarseY = bestDy
        for (dy in coarseY - 2..coarseY + 2) {
            for (dx in coarseX - 2..coarseX + 2) {
                if (abs(dx) > MAX_SHIFT + 2 || abs(dy) > MAX_SHIFT + 2) continue
                val value = score(previous, current, dx, dy)
                if (value < bestScore) {
                    secondScore = bestScore
                    bestScore = value
                    bestDx = dx
                    bestDy = dy
                } else if (value < secondScore && (dx != bestDx || dy != bestDy)) {
                    secondScore = value
                }
            }
        }

        if (bestScore > MAX_AVERAGE_ERROR) return null
        val improvement = zeroScore - bestScore
        val separation = secondScore - bestScore
        if ((bestDx != 0 || bestDy != 0) && improvement < MIN_IMPROVEMENT) return null
        if (bestDx == 0 && bestDy == 0) return null

        val confidence = ((improvement + separation) / 20f).coerceIn(0f, 1f)
        return Triple(bestDx, bestDy, confidence)
    }

    private fun score(previous: Frame, current: Frame, dx: Int, dy: Int): Float {
        val width = current.width
        val height = current.height
        val left = max(MARGIN, MARGIN - dx)
        val right = minOf(width - MARGIN, width - MARGIN - dx)
        val top = max(MARGIN, MARGIN - dy)
        val bottom = minOf(height - MARGIN, height - MARGIN - dy)
        if (right <= left || bottom <= top) return Float.MAX_VALUE

        var total = 0L
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val oldValue = previous.pixels[y * width + x].toInt() and 0xFF
                val newValue = current.pixels[(y + dy) * width + x + dx].toInt() and 0xFF
                total += abs(oldValue - newValue)
                count++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return if (count == 0) Float.MAX_VALUE else total.toFloat() / count
    }

    companion object {
        private const val MAX_SHIFT = 8
        private const val SAMPLE_STEP = 4
        private const val MARGIN = 6
        private const val MAX_AVERAGE_ERROR = 38f
        private const val MIN_IMPROVEMENT = 1.4f
    }
}
