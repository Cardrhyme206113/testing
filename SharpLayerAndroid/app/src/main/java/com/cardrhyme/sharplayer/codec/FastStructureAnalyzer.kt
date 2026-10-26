package com.cardrhyme.sharplayer.codec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fast Option D analysis schedule.
 *
 * Structure is emitted at 5 Hz, while the expensive neural maps refresh less
 * often and are motion-propagated between inference frames.
 */
object FastStructureAnalyzer {
    const val UPDATE_FPS = 5
    const val DEPTH_REFRESH_FPS = 1f
    const val SEGMENTATION_REFRESH_FPS = 0.5f

    private const val FRAME_INTERVAL_MS = 200L
    private const val KEYFRAME_INTERVAL = 10 // 2 seconds at 5 Hz
    private const val DEPTH_INTERVAL = 5
    private const val SEGMENTATION_INTERVAL = 10

    suspend fun analyze(
        context: Context,
        source: Uri,
        structureBudgetKbps: Int,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ): StructureCodec.AnalysisResult = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        val models = FastVisionModels(context)
        try {
            retriever.setDataSource(context, source)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L)
                ?: error("Could not read video duration")

            var sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 0
            var sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val swap = sourceWidth
                sourceWidth = sourceHeight
                sourceHeight = swap
            }
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                val first = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: error("Could not decode the first video frame")
                sourceWidth = first.width
                sourceHeight = first.height
                first.recycle()
            }

            val analysisWidth = when {
                structureBudgetKbps <= 64 -> 144
                structureBudgetKbps <= 110 -> 192
                else -> 224
            }
            val rawHeight = (analysisWidth * sourceHeight.toFloat() / sourceWidth.toFloat()).roundToInt()
            val analysisHeight = rawHeight.coerceIn(64, 252).let { if (it % 2 == 0) it else it + 1 }

            val frameCount = max(1, ceil(durationMs / FRAME_INTERVAL_MS.toDouble()).toInt())
            val pixelCount = analysisWidth * analysisHeight
            val previousState = ByteArray(pixelCount)
            val previousStrength = FloatArray(pixelCount)
            val frames = ArrayList<StructureCodec.Frame>(frameCount)
            var activeTotal = 0L
            var changedTotal = 0L

            var previousGray: FloatArray? = null
            var depth = FloatArray(pixelCount)
            var labels = ByteArray(pixelCount)
            var haveDepth = false
            var haveLabels = false

            val desiredDensity = (structureBudgetKbps / 900f).coerceIn(0.007f, 0.052f)
            val onHysteresis = 0.93f
            val offHysteresis = 0.66f

            for (frameIndex in 0 until frameCount) {
                check(!isCancelled()) { "Cancelled" }
                val timeMs = min(durationMs - 1L, frameIndex * FRAME_INTERVAL_MS)
                val runDepth = !haveDepth || frameIndex % DEPTH_INTERVAL == 0
                val runSegmentation = !haveLabels || frameIndex % SEGMENTATION_INTERVAL == 0
                val refresh = buildString {
                    if (runDepth) append("depth")
                    if (runDepth && runSegmentation) append(" + ")
                    if (runSegmentation) append("segmentation")
                    if (isEmpty()) append("motion propagation")
                }
                onProgress(
                    frameIndex / frameCount.toFloat(),
                    "Fast Option D ${frameIndex + 1}/$frameCount · $refresh · ${models.acceleratorSummary}",
                )

                val frame = scaledFrame(
                    retriever = retriever,
                    timeUs = timeMs * 1_000L,
                    width = analysisWidth,
                    height = analysisHeight,
                ) ?: continue

                val gray = grayscale(frame)
                val line = sobel(gray, analysisWidth, analysisHeight)
                normalizeRobust(line)

                val oldGray = previousGray
                if (oldGray != null) {
                    val (dx, dy) = estimateGlobalShift(
                        previous = oldGray,
                        current = gray,
                        width = analysisWidth,
                        height = analysisHeight,
                        maxShift = 4,
                    )
                    if (haveDepth) depth = warpFloat(depth, analysisWidth, analysisHeight, dx, dy)
                    if (haveLabels) labels = warpLabels(labels, analysisWidth, analysisHeight, dx, dy)
                }

                if (runDepth) {
                    val fresh = models.estimateDepth(frame, analysisWidth, analysisHeight)
                    if (haveDepth) {
                        for (i in fresh.indices) fresh[i] = fresh[i] * 0.76f + depth[i] * 0.24f
                    }
                    depth = fresh
                    haveDepth = true
                }

                if (runSegmentation) {
                    labels = models.segment(frame, analysisWidth, analysisHeight)
                    haveLabels = true
                }

                val depthEdge = sobel(depth, analysisWidth, analysisHeight)
                normalizeRobust(depthEdge)
                val depthNormal = laplacianMagnitude(depth, analysisWidth, analysisHeight)
                normalizeRobust(depthNormal)
                val semantic = semanticBoundaries(labels, analysisWidth, analysisHeight)
                val localMean = localMean(gray, analysisWidth, analysisHeight)

                val strength = FloatArray(pixelCount)
                for (i in 0 until pixelCount) {
                    val geometry = max(depthEdge[i], depthNormal[i] * 0.82f)
                    strength[i] = (
                        line[i] * 0.55f +
                            geometry * 0.42f +
                            semantic[i] * 0.58f
                        ).coerceIn(0f, 1f)
                }

                val threshold = quantileThreshold(strength, desiredDensity)
                val nextState = ByteArray(pixelCount)
                var active = 0
                var changed = 0
                for (i in 0 until pixelCount) {
                    val smoothed = previousStrength[i] * 0.52f + strength[i] * 0.48f
                    previousStrength[i] = smoothed
                    val wasActive = previousState[i].toInt() != 0
                    val activeNow = if (wasActive) {
                        smoothed >= threshold * offHysteresis
                    } else {
                        smoothed >= threshold * onHysteresis
                    }
                    val state = if (!activeNow) {
                        0
                    } else if (semantic[i] > 0.5f || depthEdge[i] > 0.78f) {
                        3
                    } else if (gray[i] < localMean[i]) {
                        1
                    } else {
                        2
                    }
                    nextState[i] = state.toByte()
                    if (state != 0) active++
                    if (nextState[i] != previousState[i]) changed++
                }

                val keyframe = frameIndex % KEYFRAME_INTERVAL == 0
                val payload = if (keyframe) {
                    packTwoBit(nextState)
                } else {
                    encodeDelta(previousState, nextState)
                }
                frames += StructureCodec.Frame(timeMs.toInt(), keyframe, payload)
                nextState.copyInto(previousState)
                activeTotal += active
                changedTotal += changed
                previousGray = gray
                frame.recycle()
            }

            val sequence = StructureCodec.Sequence(
                width = analysisWidth,
                height = analysisHeight,
                durationMs = durationMs,
                fps = UPDATE_FPS,
                frames = frames,
            )
            val encoded = StructureCodec.encode(sequence)
            val seconds = durationMs / 1_000.0
            val bitrate = ((encoded.size * 8.0 / seconds) / 1_000.0).roundToInt()
            val denominator = max(1L, frames.size.toLong() * pixelCount.toLong())
            StructureCodec.AnalysisResult(
                sequence = sequence,
                encoded = encoded,
                bitrateKbps = bitrate,
                activePixelPercent = activeTotal * 100f / denominator,
                changedPixelPercent = changedTotal * 100f / denominator,
            )
        } finally {
            retriever.release()
            models.close()
        }
    }

    private fun scaledFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        width: Int,
        height: Int,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                width,
                height,
            )?.let { return it }
        }
        val original = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val scaled = Bitmap.createScaledBitmap(original, width, height, true)
        if (scaled !== original) original.recycle()
        return scaled
    }

    /** Returns translation from previous coordinates into current coordinates. */
    private fun estimateGlobalShift(
        previous: FloatArray,
        current: FloatArray,
        width: Int,
        height: Int,
        maxShift: Int,
    ): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var bestError = Double.MAX_VALUE
        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                var error = 0.0
                var count = 0
                var y = 6
                while (y < height - 6) {
                    val py = y - dy
                    if (py in 0 until height) {
                        var x = 6
                        while (x < width - 6) {
                            val px = x - dx
                            if (px in 0 until width) {
                                error += abs(current[y * width + x] - previous[py * width + px])
                                count++
                            }
                            x += 4
                        }
                    }
                    y += 4
                }
                if (count > 0) error /= count
                if (error < bestError) {
                    bestError = error
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    private fun warpFloat(source: FloatArray, width: Int, height: Int, dx: Int, dy: Int): FloatArray {
        if (dx == 0 && dy == 0) return source
        val out = FloatArray(source.size)
        for (y in 0 until height) {
            val sy = (y - dy).coerceIn(0, height - 1)
            for (x in 0 until width) {
                val sx = (x - dx).coerceIn(0, width - 1)
                out[y * width + x] = source[sy * width + sx]
            }
        }
        return out
    }

    private fun warpLabels(source: ByteArray, width: Int, height: Int, dx: Int, dy: Int): ByteArray {
        if (dx == 0 && dy == 0) return source
        val out = ByteArray(source.size)
        for (y in 0 until height) {
            val sy = (y - dy).coerceIn(0, height - 1)
            for (x in 0 until width) {
                val sx = (x - dx).coerceIn(0, width - 1)
                out[y * width + x] = source[sy * width + sx]
            }
        }
        return out
    }

    private fun grayscale(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FloatArray(pixels.size) { i ->
            val p = pixels[i]
            (Color.red(p) * 0.2126f + Color.green(p) * 0.7152f + Color.blue(p) * 0.0722f) / 255f
        }
    }

    private fun sobel(source: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(source.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val a = source[i - width - 1]
                val b = source[i - width]
                val c = source[i - width + 1]
                val d = source[i - 1]
                val f = source[i + 1]
                val g = source[i + width - 1]
                val h = source[i + width]
                val j = source[i + width + 1]
                val gx = -a + c - 2f * d + 2f * f - g + j
                val gy = -a - 2f * b - c + g + 2f * h + j
                out[i] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    private fun laplacianMagnitude(source: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(source.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                out[i] = abs(
                    source[i - 1] + source[i + 1] + source[i - width] + source[i + width] - 4f * source[i]
                )
            }
        }
        return out
    }

    private fun semanticBoundaries(labels: ByteArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(labels.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val value = labels[i]
                if (labels[i - 1] != value || labels[i + 1] != value ||
                    labels[i - width] != value || labels[i + width] != value
                ) {
                    out[i] = 1f
                }
            }
        }
        return out
    }

    private fun localMean(source: FloatArray, width: Int, height: Int): FloatArray {
        val out = source.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                out[i] = (
                    source[i - width - 1] + source[i - width] + source[i - width + 1] +
                        source[i - 1] + source[i] + source[i + 1] +
                        source[i + width - 1] + source[i + width] + source[i + width + 1]
                    ) / 9f
            }
        }
        return out
    }

    private fun normalizeRobust(values: FloatArray) {
        if (values.isEmpty()) return
        val sampleStep = max(1, values.size / 4_096)
        val sample = FloatArray((values.size + sampleStep - 1) / sampleStep)
        var p = 0
        var i = 0
        while (i < values.size) {
            sample[p++] = values[i]
            i += sampleStep
        }
        sample.sort(0, p)
        val scale = sample[((p - 1) * 0.96f).roundToInt().coerceIn(0, p - 1)].coerceAtLeast(1e-6f)
        for (index in values.indices) values[index] = (values[index] / scale).coerceIn(0f, 1f)
    }

    private fun quantileThreshold(values: FloatArray, desiredDensity: Float): Float {
        val step = max(1, values.size / 4_096)
        val sample = FloatArray((values.size + step - 1) / step)
        var count = 0
        var i = 0
        while (i < values.size) {
            sample[count++] = values[i]
            i += step
        }
        sample.sort(0, count)
        val quantile = (1f - desiredDensity).coerceIn(0f, 1f)
        return sample[((count - 1) * quantile).roundToInt().coerceIn(0, count - 1)]
            .coerceIn(0.08f, 0.98f)
    }

    private fun packTwoBit(values: ByteArray): ByteArray {
        val out = ByteArray((values.size + 3) / 4)
        for (i in values.indices) {
            out[i / 4] = (out[i / 4].toInt() or ((values[i].toInt() and 3) shl ((i % 4) * 2))).toByte()
        }
        return out
    }

    private fun encodeDelta(previous: ByteArray, next: ByteArray): ByteArray {
        val changed = IntArray(next.size)
        var count = 0
        for (i in next.indices) if (next[i] != previous[i]) changed[count++] = i
        val out = java.io.ByteArrayOutputStream(max(8, count * 2))
        writeVarInt(out, count)
        var last = -1
        repeat(count) { n ->
            val index = changed[n]
            writeVarInt(out, index - last - 1)
            out.write(next[index].toInt())
            last = index
        }
        return out.toByteArray()
    }

    private fun writeVarInt(out: java.io.ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (true) {
            val bits = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                out.write(bits)
                return
            }
            out.write(bits or 0x80)
        }
    }
}
