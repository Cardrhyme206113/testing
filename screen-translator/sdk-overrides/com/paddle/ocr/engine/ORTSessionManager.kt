package com.paddle.ocr.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.OCRExecutionProvider
import com.paddle.ocr.model.OCRError
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet

class ORTSessionManager(
    private val context: Context,
    private val config: EngineConfig,
) {
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var detInputName: String = "x"
    private var recInputName: String = "x"

    var coldLoadTimeMs: Long = 0
        private set

    var activeProviderName: String = "CPU"
        private set

    fun loadModels(detAssetPath: String, recAssetPath: String) {
        val loadStart = System.currentTimeMillis()
        env = OrtEnvironment.getEnvironment()
        val detBytes = readModel(detAssetPath)
        val recBytes = readModel(recAssetPath)

        val shouldTryNnapi = config.executionProvider != OCRExecutionProvider.CPU
        if (shouldTryNnapi) {
            val nnapiResult = runCatching {
                createSessions(detBytes, recBytes, useNnapi = true)
            }
            if (nnapiResult.isSuccess) {
                activeProviderName = if (config.allowFp16) "NNAPI FP16" else "NNAPI"
            } else {
                closeSessions()
                createSessions(detBytes, recBytes, useNnapi = false)
                activeProviderName = "CPU fallback"
            }
        } else {
            createSessions(detBytes, recBytes, useNnapi = false)
            activeProviderName = "CPU"
        }

        detInputName = detSession?.inputNames?.iterator()?.next()
            ?: throw OCRError.ModelLoadFailed("detection", Exception("No detection input"))
        recInputName = recSession?.inputNames?.iterator()?.next()
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("No recognition input"))
        coldLoadTimeMs = System.currentTimeMillis() - loadStart
    }

    private fun createSessions(detBytes: ByteArray, recBytes: ByteArray, useNnapi: Boolean) {
        val ortEnv = env ?: throw OCRError.ModelLoadFailed("OCR", Exception("Environment not initialized"))
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setIntraOpNumThreads(config.numThreads.coerceAtLeast(1))
            if (useNnapi) {
                val flags = if (config.allowFp16) {
                    EnumSet.of(NNAPIFlags.USE_FP16)
                } else {
                    EnumSet.noneOf(NNAPIFlags::class.java)
                }
                addNnapi(flags)
            }
        }

        try {
            detSession = try {
                ortEnv.createSession(detBytes, options)
            } catch (error: Throwable) {
                throw OCRError.ModelLoadFailed("detection", error)
            }
            recSession = try {
                ortEnv.createSession(recBytes, options)
            } catch (error: Throwable) {
                runCatching { detSession?.close() }
                detSession = null
                throw OCRError.ModelLoadFailed("recognition", error)
            }
        } finally {
            options.close()
        }
    }

    fun runDetection(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = detSession
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Environment not initialized"))
        return runSession(ortEnv, session, detInputName, input, shape, "detection")
    }

    fun runRecognition(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = recSession
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Environment not initialized"))
        return runSession(ortEnv, session, recInputName, input, shape, "recognition")
    }

    fun release() {
        closeSessions()
        env = null
    }

    private fun closeSessions() {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        detSession = null
        recSession = null
    }

    private fun readModel(path: String): ByteArray {
        val file = File(path)
        if (file.isFile) {
            return try {
                file.readBytes()
            } catch (error: Throwable) {
                throw OCRError.ModelNotFound(path, error)
            }
        }
        return try {
            context.assets.open(path).use { it.readBytes() }
        } catch (error: Throwable) {
            throw OCRError.ModelNotFound(path, error)
        }
    }

    private fun runSession(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        modelName: String,
    ): Pair<FloatArray, LongArray> {
        val tensor = try {
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
        } catch (error: Throwable) {
            throw OCRError.InferenceFailed(modelName, error)
        }

        val result = try {
            try {
                session.run(mapOf(inputName to tensor))
            } catch (error: Throwable) {
                throw OCRError.InferenceFailed(modelName, error)
            }
        } finally {
            tensor.close()
        }

        return try {
            val outputName = session.outputNames.iterator().next()
            val ortValue = result.get(outputName)
                .orElseThrow { Exception("No output tensor found") }
            val outputTensor = ortValue as? OnnxTensor
                ?: throw Exception("Output is not an ONNX tensor")
            Pair(copyFloatBuffer(outputTensor.floatBuffer), outputTensor.info.shape)
        } catch (error: Throwable) {
            throw OCRError.InferenceFailed(modelName, error)
        } finally {
            result.close()
        }
    }

    private fun copyFloatBuffer(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        return FloatArray(duplicate.remaining()).also { duplicate.get(it) }
    }
}
