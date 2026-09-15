package com.cardrhyme.sharplayer.codec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object StructureCodec {
    const val UPDATE_FPS = 10
    private const val FRAME_INTERVAL_MS = 100L
    private const val KEYFRAME_INTERVAL = 10
    private const val STREAM_VERSION = 1
    private const val FOOTER_SIZE = 20
    private val STREAM_MAGIC = byteArrayOf('S'.code.toByte(), 'L'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())
    private val FOOTER_MAGIC = byteArrayOf(
        'S'.code.toByte(), 'L'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte(),
        'F'.code.toByte(), 'T'.code.toByte(), 'R'.code.toByte(), '!'.code.toByte()
    )

    data class Frame(
        val timestampMs: Int,
        val keyframe: Boolean,
        val payload: ByteArray
    )

    data class Sequence(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val fps: Int,
        val frames: List<Frame>
    )

    data class AnalysisResult(
        val sequence: Sequence,
        val encoded: ByteArray,
        val bitrateKbps: Int,
        val activePixelPercent: Float,
        val changedPixelPercent: Float
    )

    data class ContainerInfo(
        val baseLength: Long,
        val structureLength: Long,
        val metadataLength: Int,
        val metadata: JSONObject
    )

    data class PreparedMedia(
        val baseFile: File,
        val sequence: Sequence?,
        val metadata: JSONObject?,
        val cleanup: List<File>
    ) : AutoCloseable {
        override fun close() {
            cleanup.forEach { runCatching { it.delete() } }
        }
    }

    suspend fun analyze(
        context: Context,
        source: Uri,
        structureBudgetKbps: Int,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        val models = VisionModels(context)
        try {
            retriever.setDataSource(context, source)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L)
                ?: error("Could not read video duration")

            val first = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Could not decode the first video frame")
            val analysisWidth = when {
                structureBudgetKbps <= 28 -> 144
                structureBudgetKbps <= 55 -> 192
                else -> 256
            }
            val rawHeight = (analysisWidth * first.height.toFloat() / first.width.toFloat()).roundToInt()
            val analysisHeight = rawHeight.coerceIn(64, 288).let { if (it % 2 == 0) it else it + 1 }
            first.recycle()

            val frameCount = max(1, ceil(durationMs / FRAME_INTERVAL_MS.toDouble()).toInt())
            val pixelCount = analysisWidth * analysisHeight
            val previousState = ByteArray(pixelCount)
            val previousStrength = FloatArray(pixelCount)
            val frames = ArrayList<Frame>(frameCount)
            var activeTotal = 0L
            var changedTotal = 0L

            val desiredDensity = (structureBudgetKbps / 1_350f).coerceIn(0.006f, 0.045f)
            val onHysteresis = 0.92f
            val offHysteresis = 0.68f

            for (frameIndex in 0 until frameCount) {
                check(!isCancelled()) { "Cancelled" }
                val timeMs = min(durationMs - 1L, frameIndex * FRAME_INTERVAL_MS)
                onProgress(
                    frameIndex / frameCount.toFloat(),
                    "Option D analysis ${frameIndex + 1}/$frameCount · depth + segmentation + lineart"
                )

                val original = retriever.getFrameAtTime(
                    timeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val frame = Bitmap.createScaledBitmap(original, analysisWidth, analysisHeight, true)
                if (frame !== original) original.recycle()

                val gray = grayscale(frame)
                val line = sobel(gray, analysisWidth, analysisHeight)
                normalizeRobust(line)

                val depth = models.estimateDepth(frame, analysisWidth, analysisHeight)
                val depthEdge = sobel(depth, analysisWidth, analysisHeight)
                normalizeRobust(depthEdge)
                val depthNormal = laplacianMagnitude(depth, analysisWidth, analysisHeight)
                normalizeRobust(depthNormal)

                val labels = models.segment(frame, analysisWidth, analysisHeight)
                val semantic = semanticBoundaries(labels, analysisWidth, analysisHeight)
                val localMean = localMean(gray, analysisWidth, analysisHeight)

                val strength = FloatArray(pixelCount)
                for (i in 0 until pixelCount) {
                    val geometry = max(depthEdge[i], depthNormal[i] * 0.82f)
                    strength[i] = (
                        line[i] * 0.52f +
                            geometry * 0.43f +
                            semantic[i] * 0.62f
                        ).coerceIn(0f, 1f)
                }

                val threshold = quantileThreshold(strength, desiredDensity)
                val nextState = ByteArray(pixelCount)
                var active = 0
                var changed = 0
                for (i in 0 until pixelCount) {
                    val smoothed = previousStrength[i] * 0.58f + strength[i] * 0.42f
                    previousStrength[i] = smoothed
                    val wasActive = previousState[i].toInt() != 0
                    val keepThreshold = threshold * offHysteresis
                    val startThreshold = threshold * onHysteresis
                    val activeNow = if (wasActive) smoothed >= keepThreshold else smoothed >= startThreshold
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
                frames += Frame(timeMs.toInt(), keyframe, payload)
                nextState.copyInto(previousState)
                activeTotal += active
                changedTotal += changed
                frame.recycle()
            }

            val sequence = Sequence(
                width = analysisWidth,
                height = analysisHeight,
                durationMs = durationMs,
                fps = UPDATE_FPS,
                frames = frames
            )
            val encoded = encode(sequence)
            val seconds = durationMs / 1_000.0
            val bitrate = ((encoded.size * 8.0 / seconds) / 1_000.0).roundToInt()
            val denominator = max(1L, frames.size.toLong() * pixelCount.toLong())
            AnalysisResult(
                sequence = sequence,
                encoded = encoded,
                bitrateKbps = bitrate,
                activePixelPercent = activeTotal * 100f / denominator,
                changedPixelPercent = changedTotal * 100f / denominator
            )
        } finally {
            retriever.release()
            models.close()
        }
    }

    fun encode(sequence: Sequence): ByteArray {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(BufferedOutputStream(raw), 64 * 1024).use { gzip ->
            DataOutputStream(gzip).use { out ->
                out.write(STREAM_MAGIC)
                out.writeInt(STREAM_VERSION)
                out.writeInt(sequence.width)
                out.writeInt(sequence.height)
                out.writeInt(sequence.fps)
                out.writeLong(sequence.durationMs)
                out.writeInt(sequence.frames.size)
                sequence.frames.forEach { frame ->
                    out.writeInt(frame.timestampMs)
                    out.writeBoolean(frame.keyframe)
                    out.writeInt(frame.payload.size)
                    out.write(frame.payload)
                }
            }
        }
        return raw.toByteArray()
    }

    fun decode(bytes: ByteArray): Sequence {
        DataInputStream(GZIPInputStream(BufferedInputStream(ByteArrayInputStream(bytes)), 64 * 1024)).use { input ->
            val magic = ByteArray(STREAM_MAGIC.size)
            input.readFully(magic)
            require(magic.contentEquals(STREAM_MAGIC)) { "Not a SharpLayer structure stream" }
            val version = input.readInt()
            require(version == STREAM_VERSION) { "Unsupported structure version $version" }
            val width = input.readInt()
            val height = input.readInt()
            val fps = input.readInt()
            val durationMs = input.readLong()
            val frameCount = input.readInt().coerceIn(0, 1_000_000)
            val frames = ArrayList<Frame>(frameCount)
            repeat(frameCount) {
                val timestamp = input.readInt()
                val keyframe = input.readBoolean()
                val length = input.readInt().coerceIn(0, 64 * 1024 * 1024)
                val payload = ByteArray(length)
                input.readFully(payload)
                frames += Frame(timestamp, keyframe, payload)
            }
            return Sequence(width, height, durationMs, fps, frames)
        }
    }

    fun pack(
        baseMp4: File,
        structure: ByteArray,
        output: File,
        metadata: JSONObject,
        onProgress: (Int) -> Unit = {}
    ) {
        val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)
        val total = baseMp4.length() + structure.size
        var copied = 0L
        output.outputStream().buffered(1024 * 1024).use { out ->
            baseMp4.inputStream().buffered(1024 * 1024).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    onProgress(((copied * 100L) / max(1L, total)).toInt())
                }
            }
            out.write(structure)
            copied += structure.size
            onProgress(((copied * 100L) / max(1L, total)).toInt())
            out.write(metadataBytes)
            val footer = ByteBuffer.allocate(FOOTER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put(FOOTER_MAGIC)
                .putLong(structure.size.toLong())
                .putInt(metadataBytes.size)
                .array()
            out.write(footer)
        }
    }

    fun inspect(file: File): ContainerInfo? {
        if (file.length() < FOOTER_SIZE) return null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - FOOTER_SIZE)
            val footer = ByteArray(FOOTER_SIZE)
            raf.readFully(footer)
            val bb = ByteBuffer.wrap(footer).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(8)
            bb.get(magic)
            if (!magic.contentEquals(FOOTER_MAGIC)) return null
            val structureLength = bb.long
            val metadataLength = bb.int
            if (structureLength <= 0L || metadataLength <= 0) return null
            val metadataStart = file.length() - FOOTER_SIZE - metadataLength
            val baseLength = metadataStart - structureLength
            if (baseLength <= 0L) return null
            raf.seek(metadataStart)
            val metadataBytes = ByteArray(metadataLength)
            raf.readFully(metadataBytes)
            return ContainerInfo(
                baseLength,
                structureLength,
                metadataLength,
                JSONObject(metadataBytes.toString(Charsets.UTF_8))
            )
        }
    }

    suspend fun prepareForPlayback(context: Context, uri: Uri): PreparedMedia = withContext(Dispatchers.IO) {
        val local = File(context.cacheDir, "sharplayer-open-${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open selected video" }
            local.outputStream().buffered(1024 * 1024).use { output -> input.copyTo(output) }
        }
        val info = inspect(local)
        if (info == null) {
            return@withContext PreparedMedia(local, null, null, listOf(local))
        }

        val base = File(context.cacheDir, "sharplayer-base-${System.currentTimeMillis()}.mp4")
        val structureBytes = ByteArray(info.structureLength.toInt())
        RandomAccessFile(local, "r").use { raf ->
            base.outputStream().buffered(1024 * 1024).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var remaining = info.baseLength
                while (remaining > 0) {
                    val read = raf.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) error("Unexpected end of layered file")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            raf.readFully(structureBytes)
        }
        PreparedMedia(base, decode(structureBytes), info.metadata, listOf(local, base))
    }

    class PlaybackDecoder(private val sequence: Sequence) {
        private val state = ByteArray(sequence.width * sequence.height)
        private var appliedIndex = -1

        fun stateAt(positionMs: Long): ByteArray {
            if (sequence.frames.isEmpty()) return state
            val target = findFrame(positionMs)
            if (target < appliedIndex || target - appliedIndex > KEYFRAME_INTERVAL * 2) {
                val key = findKeyframe(target)
                state.fill(0)
                appliedIndex = key - 1
            }
            while (appliedIndex < target) {
                appliedIndex++
                apply(sequence.frames[appliedIndex], state)
            }
            return state
        }

        private fun findFrame(positionMs: Long): Int {
            var low = 0
            var high = sequence.frames.lastIndex
            while (low < high) {
                val mid = (low + high + 1) ushr 1
                if (sequence.frames[mid].timestampMs <= positionMs) low = mid else high = mid - 1
            }
            return low
        }

        private fun findKeyframe(target: Int): Int {
            for (i in target downTo 0) if (sequence.frames[i].keyframe) return i
            return 0
        }
    }

    private fun apply(frame: Frame, state: ByteArray) {
        if (frame.keyframe) {
            unpackTwoBit(frame.payload, state)
            return
        }
        val input = ByteArrayInputStream(frame.payload)
        val count = readVarInt(input)
        var index = -1
        repeat(count) {
            index += readVarInt(input) + 1
            if (index in state.indices) state[index] = input.read().coerceAtLeast(0).toByte()
        }
    }

    private fun grayscale(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FloatArray(pixels.size) { i ->
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            (r * 0.2126f + g * 0.7152f + b * 0.0722f) / 255f
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

    private fun unpackTwoBit(packed: ByteArray, output: ByteArray) {
        for (i in output.indices) {
            output[i] = ((packed[i / 4].toInt() ushr ((i % 4) * 2)) and 3).toByte()
        }
    }

    private fun encodeDelta(previous: ByteArray, next: ByteArray): ByteArray {
        val changed = IntArray(next.size)
        var count = 0
        for (i in next.indices) if (next[i] != previous[i]) changed[count++] = i
        val out = ByteArrayOutputStream(max(8, count * 2))
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

    private fun writeVarInt(out: ByteArrayOutputStream, raw: Int) {
        var value = raw
        while (value and -128 != 0) {
            out.write((value and 127) or 128)
            value = value ushr 7
        }
        out.write(value)
    }

    private fun readVarInt(input: ByteArrayInputStream): Int {
        var value = 0
        var shift = 0
        while (shift < 35) {
            val b = input.read()
            if (b < 0) return value
            value = value or ((b and 127) shl shift)
            if (b and 128 == 0) return value
            shift += 7
        }
        return value
    }
}
