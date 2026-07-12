package com.cardrhyme.sharplayer.codec

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * On-device perception used by the experimental Option D structure layer.
 *
 * - MiDaS produces relative monocular depth.
 * - DeepLab-v3 produces semantic region labels.
 * - Line-art itself is generated deterministically from the source frame so it
 *   stays faithful instead of hallucinating details.
 */
class VisionModels(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val depthInterpreter = Interpreter(
        loadModel("midas.tflite"),
        Interpreter.Options().apply { setNumThreads(4) }
    )
    private val segmentationInterpreter = Interpreter(
        loadModel("deeplab_v3.tflite"),
        Interpreter.Options().apply { setNumThreads(4) }
    )

    fun estimateDepth(bitmap: Bitmap, outputWidth: Int, outputHeight: Int): FloatArray {
        val inputTensor = depthInterpreter.getInputTensor(0)
        val shape = inputTensor.shape()
        require(shape.size >= 4) { "Unsupported MiDaS input shape: ${shape.contentToString()}" }
        val modelHeight = shape[1]
        val modelWidth = shape[2]
        val input = bitmapInput(bitmap, modelWidth, modelHeight, inputTensor.dataType())

        val outputTensor = depthInterpreter.getOutputTensor(0)
        val output = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
        depthInterpreter.run(input, output)
        output.rewind()

        val outputShape = outputTensor.shape()
        val raw = when (outputTensor.dataType()) {
            DataType.FLOAT32 -> FloatArray(outputTensor.numElements()).also {
                output.asFloatBuffer().get(it)
            }
            DataType.UINT8 -> FloatArray(outputTensor.numElements()) { i ->
                (output.get(i).toInt() and 0xFF) / 255f
            }
            else -> error("Unsupported MiDaS output type: ${outputTensor.dataType()}")
        }

        val outHeight: Int
        val outWidth: Int
        when (outputShape.size) {
            4 -> {
                outHeight = outputShape[1]
                outWidth = outputShape[2]
            }
            3 -> {
                outHeight = outputShape[1]
                outWidth = outputShape[2]
            }
            2 -> {
                outHeight = outputShape[0]
                outWidth = outputShape[1]
            }
            else -> {
                outHeight = modelHeight
                outWidth = modelWidth
            }
        }

        val spatial = if (raw.size == outWidth * outHeight) {
            raw
        } else {
            // Some exported models keep a singleton channel dimension.
            FloatArray(outWidth * outHeight) { i -> raw[min(i, raw.lastIndex)] }
        }
        normalizeInPlace(spatial)
        return resizeFloat(spatial, outWidth, outHeight, outputWidth, outputHeight)
    }

    fun segment(bitmap: Bitmap, outputWidth: Int, outputHeight: Int): ByteArray {
        val inputTensor = segmentationInterpreter.getInputTensor(0)
        val shape = inputTensor.shape()
        require(shape.size >= 4) { "Unsupported DeepLab input shape: ${shape.contentToString()}" }
        val modelHeight = shape[1]
        val modelWidth = shape[2]
        val input = bitmapInput(bitmap, modelWidth, modelHeight, inputTensor.dataType())

        val outputTensor = segmentationInterpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        val output = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
        segmentationInterpreter.run(input, output)
        output.rewind()

        val spatialHeight = when {
            outputShape.size >= 3 -> outputShape[outputShape.size - 3]
            else -> modelHeight
        }
        val spatialWidth = when {
            outputShape.size >= 2 -> outputShape[outputShape.size - 2]
            else -> modelWidth
        }
        val channels = when {
            outputShape.size >= 4 -> outputShape.last()
            else -> 1
        }

        val labels = ByteArray(spatialWidth * spatialHeight)
        when (outputTensor.dataType()) {
            DataType.UINT8 -> {
                if (channels <= 1) {
                    output.get(labels)
                } else {
                    for (i in labels.indices) {
                        var best = 0
                        var bestValue = -1
                        repeat(channels) { c ->
                            val value = output.get().toInt() and 0xFF
                            if (value > bestValue) {
                                bestValue = value
                                best = c
                            }
                        }
                        labels[i] = best.toByte()
                    }
                }
            }
            DataType.FLOAT32 -> {
                val values = FloatArray(outputTensor.numElements())
                output.asFloatBuffer().get(values)
                if (channels <= 1) {
                    for (i in labels.indices) {
                        labels[i] = values[min(i, values.lastIndex)].toInt().coerceIn(0, 255).toByte()
                    }
                } else {
                    for (i in labels.indices) {
                        var best = 0
                        var bestValue = -Float.MAX_VALUE
                        val base = i * channels
                        repeat(channels) { c ->
                            val value = values[min(base + c, values.lastIndex)]
                            if (value > bestValue) {
                                bestValue = value
                                best = c
                            }
                        }
                        labels[i] = best.toByte()
                    }
                }
            }
            else -> error("Unsupported DeepLab output type: ${outputTensor.dataType()}")
        }

        return resizeLabels(labels, spatialWidth, spatialHeight, outputWidth, outputHeight)
    }

    override fun close() {
        depthInterpreter.close()
        segmentationInterpreter.close()
    }

    private fun bitmapInput(bitmap: Bitmap, width: Int, height: Int, type: DataType): ByteBuffer {
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        val bytesPerChannel = if (type == DataType.FLOAT32) 4 else 1
        val buffer = ByteBuffer.allocateDirect(width * height * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
        when (type) {
            DataType.FLOAT32 -> pixels.forEach { p ->
                buffer.putFloat(((p ushr 16) and 0xFF) / 255f)
                buffer.putFloat(((p ushr 8) and 0xFF) / 255f)
                buffer.putFloat((p and 0xFF) / 255f)
            }
            DataType.UINT8 -> pixels.forEach { p ->
                buffer.put(((p ushr 16) and 0xFF).toByte())
                buffer.put(((p ushr 8) and 0xFF).toByte())
                buffer.put((p and 0xFF).toByte())
            }
            else -> error("Unsupported model input type: $type")
        }
        buffer.rewind()
        return buffer
    }

    private fun normalizeInPlace(values: FloatArray) {
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        values.forEach {
            if (it.isFinite()) {
                low = min(low, it)
                high = max(high, it)
            }
        }
        val range = (high - low).coerceAtLeast(1e-8f)
        for (i in values.indices) {
            values[i] = if (values[i].isFinite()) (values[i] - low) / range else 0f
        }
    }

    private fun resizeFloat(
        source: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) return source.copyOf()
        val out = FloatArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val fy = if (targetHeight == 1) 0f else y * (sourceHeight - 1f) / (targetHeight - 1f)
            val y0 = floor(fy).toInt().coerceIn(0, sourceHeight - 1)
            val y1 = min(y0 + 1, sourceHeight - 1)
            val wy = fy - y0
            for (x in 0 until targetWidth) {
                val fx = if (targetWidth == 1) 0f else x * (sourceWidth - 1f) / (targetWidth - 1f)
                val x0 = floor(fx).toInt().coerceIn(0, sourceWidth - 1)
                val x1 = min(x0 + 1, sourceWidth - 1)
                val wx = fx - x0
                val a = source[y0 * sourceWidth + x0]
                val b = source[y0 * sourceWidth + x1]
                val c = source[y1 * sourceWidth + x0]
                val d = source[y1 * sourceWidth + x1]
                out[y * targetWidth + x] =
                    (a * (1f - wx) + b * wx) * (1f - wy) +
                        (c * (1f - wx) + d * wx) * wy
            }
        }
        return out
    }

    private fun resizeLabels(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray {
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) return source.copyOf()
        val out = ByteArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sy = (y * sourceHeight / targetHeight).coerceIn(0, sourceHeight - 1)
            for (x in 0 until targetWidth) {
                val sx = (x * sourceWidth / targetWidth).coerceIn(0, sourceWidth - 1)
                out[y * targetWidth + x] = source[sy * sourceWidth + sx]
            }
        }
        return out
    }

    private fun loadModel(name: String): MappedByteBuffer {
        val descriptor = appContext.assets.openFd(name)
        FileInputStream(descriptor.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength
            )
        }
    }
}
