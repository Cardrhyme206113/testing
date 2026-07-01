package com.cardrhyme.screentranslator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.OCRExecutionProvider
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.LinkedHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

internal data class OcrOutput(
    val items: List<OverlayItem>,
    val detectedCount: Int,
    val japaneseCount: Int,
    val elapsedMs: Long,
    val modelLabel: String,
)

internal class RealtimeOcrController(
    private val context: Context,
    private val reportStatus: (String) -> Unit,
) {
    private var ocr: PaddleOCR? = null
    private lateinit var translator: Translator
    private var modelLabel = "Small NNAPI"

    private val translations = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 256
    }

    suspend fun initialize() = coroutineScope {
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build(),
        )
        val translationJob = async { downloadTranslationModel() }
        val ocrJob = async(Dispatchers.IO) {
            check(OpenCVUtils.init(context)) { "OpenCV could not initialize" }
            val modelPaths = runCatching {
                OcrModelStore.ensureSmallModels(context) { reportStatus(it) }
            }.getOrNull()

            val detector = modelPaths?.detector ?: "models/det/inference.onnx"
            val recognizer = modelPaths?.recognizer ?: "models/rec/inference.onnx"
            val config = if (modelPaths == null) "models/rec/inference.yml" else "models/rec-small/inference.yml"
            modelLabel = if (modelPaths == null) "Tiny fallback" else "Small NNAPI"

            PaddleOCR.create(
                context = context,
                config = PaddleOCRConfig(
                    detLimitSideLen = 64,
                    detLimitType = "min",
                    detMaxSideLimit = 4000,
                    detThresh = 0.25f,
                    detBoxThresh = 0.45f,
                    detUnclipRatio = 1.6f,
                    detMaxCandidates = 5000,
                    detUseDilation = true,
                    detScoreMode = "slow",
                    recScoreThresh = 0.20f,
                    recBatchSize = 8,
                ),
                engineConfig = EngineConfig(
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                    executionProvider = OCRExecutionProvider.AUTO,
                    allowFp16 = true,
                ),
                detModelAssetPath = detector,
                recModelAssetPath = recognizer,
                recConfigAssetPath = config,
            )
        }
        ocr = ocrJob.await()
        translationJob.await()
    }

    suspend fun process(bitmap: Bitmap, screenWidth: Int, screenHeight: Int): OcrOutput {
        val engine = ocr ?: return OcrOutput(emptyList(), 0, 0, 0, modelLabel)
        val run = engine.recognize(bitmap)
        val regions = run.results.asSequence()
            .filter { it.confidence >= 0.20f }
            .map { result ->
                val bounds = result.toScreenBounds(bitmap, screenWidth, screenHeight)
                OcrRegion(
                    text = normalize(result.text),
                    bounds = bounds,
                    confidence = result.confidence,
                    backgroundColor = sampleBackground(bitmap, result),
                )
            }
            .filter { it.text.isNotBlank() }
            .filterNot { it.bounds.left < 430f && it.bounds.top < 100f }
            .take(96)
            .toList()

        val grouped = VerticalTextGrouper.merge(regions)
        val japaneseCount = grouped.count { containsJapanese(it.text) }
        val items = coroutineScope {
            grouped.map { region ->
                async {
                    if (!containsJapanese(region.text)) {
                        OverlayItem(region.bounds, kind = OverlayKind.HIGHLIGHT)
                    } else {
                        val english = runCatching { translate(region.text) }.getOrDefault("")
                        if (english.isBlank()) {
                            OverlayItem(region.bounds, kind = OverlayKind.HIGHLIGHT)
                        } else {
                            OverlayItem(
                                bounds = region.bounds,
                                text = english,
                                kind = OverlayKind.TRANSLATED,
                                backgroundColor = region.backgroundColor,
                                foregroundColor = contrastingColor(region.backgroundColor),
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        return OcrOutput(items, grouped.size, japaneseCount, run.totalTimeMs, modelLabel)
    }

    suspend fun close() {
        withContext(Dispatchers.IO) { ocr?.release() }
        if (::translator.isInitialized) translator.close()
    }

    private suspend fun downloadTranslationModel() {
        suspendCancellableCoroutine<Unit> { continuation ->
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }
    }

    private suspend fun translate(value: String): String {
        synchronized(translations) { translations[value]?.let { return it } }
        val result = suspendCancellableCoroutine<String> { continuation ->
            translator.translate(value)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }
        synchronized(translations) { translations[value] = result }
        return result
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun containsJapanese(value: String): Boolean = value.any {
        it.code in 0x3040..0x30FF || it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF
    }

    private fun OCRResult.toScreenBounds(bitmap: Bitmap, width: Int, height: Int): RectF {
        val sx = width.toFloat() / bitmap.width
        val sy = height.toFloat() / bitmap.height
        return RectF(
            box.points.minOf { it.x } * sx,
            box.points.minOf { it.y } * sy,
            box.points.maxOf { it.x } * sx,
            box.points.maxOf { it.y } * sy,
        )
    }

    private fun sampleBackground(bitmap: Bitmap, result: OCRResult): Int {
        val left = result.box.points.minOf { it.x }.toInt().coerceIn(0, bitmap.width - 1)
        val top = result.box.points.minOf { it.y }.toInt().coerceIn(0, bitmap.height - 1)
        val right = result.box.points.maxOf { it.x }.toInt().coerceIn(left, bitmap.width - 1)
        val bottom = result.box.points.maxOf { it.y }.toInt().coerceIn(top, bitmap.height - 1)
        val margin = max(2, max(right - left, bottom - top) / 16)
        val samples = mutableListOf<Int>()
        for (step in 0..12) {
            val x = left + (right - left) * step / 12
            val y = top + (bottom - top) * step / 12
            samples += bitmap.getPixel(x, (top - margin).coerceAtLeast(0))
            samples += bitmap.getPixel(x, (bottom + margin).coerceAtMost(bitmap.height - 1))
            samples += bitmap.getPixel((left - margin).coerceAtLeast(0), y)
            samples += bitmap.getPixel((right + margin).coerceAtMost(bitmap.width - 1), y)
        }
        if (samples.isEmpty()) return Color.rgb(24, 24, 24)
        val red = samples.map { Color.red(it) }.sorted()[samples.size / 2]
        val green = samples.map { Color.green(it) }.sorted()[samples.size / 2]
        val blue = samples.map { Color.blue(it) }.sorted()[samples.size / 2]
        return Color.rgb(red, green, blue)
    }

    private fun contrastingColor(background: Int): Int {
        val luminance = Color.red(background) * 0.299 + Color.green(background) * 0.587 + Color.blue(background) * 0.114
        return if (luminance > 145) Color.BLACK else Color.WHITE
    }
}
