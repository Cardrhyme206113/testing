package com.cardrhyme.screentranslator

import android.media.Image
import kotlin.math.abs
import kotlin.math.max

class ScreenMotionTracker(
    private val targetWidth: Int = 180,
) {
    data class Motion(val dxPixels: Float, val dyPixels: Float, val confidence: Float)

    private data class Frame(
        val pixels: ByteArray,
        val rowSignature: IntArray,
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

        val dy = estimateVertical(old, current)
        val dx = estimateHorizontal(old, current, dy)
        if (dx == 0 && dy == 0) return null

        val verticalScore = rowScore(old, current, dy)
        val zeroScore = rowScore(old, current, 0)
        val improvement = (zeroScore - verticalScore).coerceAtLeast(0f)
        val confidence = (improvement / 6000f).coerceIn(0.05f, 1f)
        return Motion(
            dxPixels = dx * current.sourceWidth.toFloat() / current.width,
            dyPixels = dy * current.sourceHeight.toFloat() / current.height,
            confidence = confidence,
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
        val rows = IntArray(height)

        for (y in 0 until height) {
            val sourceY = (y.toLong() * image.height / height).toInt().coerceAtMost(image.height - 1)
            val rowOffset = sourceY * rowStride
            var previousGray = 0
            var signature = 0
            for (x in 0 until width) {
                val sourceX = (x.toLong() * image.width / width).toInt().coerceAtMost(image.width - 1)
                val index = rowOffset + sourceX * pixelStride
                val r = buffer.get(index).toInt() and 0xFF
                val g = buffer.get(index + 1).toInt() and 0xFF
                val b = buffer.get(index + 2).toInt() and 0xFF
                val gray = (r * 54 + g * 183 + b * 19) shr 8
                pixels[y * width + x] = gray.toByte()
                if (x > 0) signature += abs(gray - previousGray)
                previousGray = gray
            }
            rows[y] = signature
        }
        return Frame(pixels, rows, width, height, image.width, image.height)
    }

    private fun estimateVertical(old: Frame, current: Frame): Int {
        var bestShift = 0
        var best = rowScore(old, current, 0)
        for (shift in -MAX_VERTICAL_SHIFT..MAX_VERTICAL_SHIFT) {
            if (shift == 0) continue
            val score = rowScore(old, current, shift)
            if (score < best) {
                best = score
                bestShift = shift
            }
        }
        return bestShift
    }

    private fun rowScore(old: Frame, current: Frame, shift: Int): Float {
        val top = max(MARGIN, MARGIN - shift)
        val bottom = minOf(old.height - MARGIN, current.height - MARGIN - shift)
        if (bottom <= top) return Float.MAX_VALUE
        var total = 0L
        var count = 0
        for (y in top until bottom) {
            total += abs(old.rowSignature[y] - current.rowSignature[y + shift])
            count++
        }
        return if (count == 0) Float.MAX_VALUE else total.toFloat() / count
    }

    private fun estimateHorizontal(old: Frame, current: Frame, dy: Int): Int {
        var bestShift = 0
        var best = pixelScore(old, current, 0, dy)
        for (dx in -MAX_HORIZONTAL_SHIFT..MAX_HORIZONTAL_SHIFT) {
            if (dx == 0) continue
            val score = pixelScore(old, current, dx, dy)
            if (score < best) {
                best = score
                bestShift = dx
            }
        }
        return bestShift
    }

    private fun pixelScore(old: Frame, current: Frame, dx: Int, dy: Int): Float {
        val left = max(MARGIN, MARGIN - dx)
        val right = minOf(old.width - MARGIN, current.width - MARGIN - dx)
        val top = max(MARGIN, MARGIN - dy)
        val bottom = minOf(old.height - MARGIN, current.height - MARGIN - dy)
        if (right <= left || bottom <= top) return Float.MAX_VALUE

        var total = 0L
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val a = old.pixels[y * old.width + x].toInt() and 0xFF
                val b = current.pixels[(y + dy) * current.width + x + dx].toInt() and 0xFF
                total += abs(a - b)
                count++
                x += 5
            }
            y += 5
        }
        return if (count == 0) Float.MAX_VALUE else total.toFloat() / count
    }

    companion object {
        private const val MAX_VERTICAL_SHIFT = 92
        private const val MAX_HORIZONTAL_SHIFT = 18
        private const val MARGIN = 8
    }
}
