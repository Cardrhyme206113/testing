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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.LinkedHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

internal data class PendingTranslation(
    val source: String,
    val item: OverlayItem,
)

internal data class OcrOutput(
    val items: List<OverlayItem>,
    val pendingTranslations: List<PendingTranslation>,
    val detectedCount: Int,
    val japaneseCount: Int,
    val elapsedMs: Long,
    val modelLabel: String,
)

internal class RealtimeOcrController(
    private val context: Context,
    private val reportStatus: (String) -> Unit,
) {
    private val translationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val translationReady = CompletableDeferred<Boolean>()
    private var ocr: PaddleOCR? = null
    private lateinit var translator: Translator
    private val modelLabel = "Tiny NNAPI full-res"

    private val translations = object : LinkedHashMap<String, String>(384, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 384
    }

    suspend fun initialize() {
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build(),
        )
        translationScope.launch {
            reportStatus("OCR readying • translator loading separately")
            val ready = runCatching { downloadTranslationModel() }.isSuccess
            if (!translationReady.isCompleted) translationReady.complete(ready)
        }

        ocr = withContext(Dispatchers.IO) {
            check(OpenCVUtils.init(context)) { "OpenCV could not initialize" }
            PaddleOCR.create(
                context = context,
                config = PaddleOCRConfig(
                    detLimitSideLen = 64,
                    detLimitType = "min",
                    detMaxSideLimit = 4000,
                    detThresh = 0.28f,
                    detBoxThresh = 0.50f,
                    detUnclipRatio = 1.50f,
                    detMaxCandidates = 2000,
                    detUseDilation = false,
                    detScoreMode = "fast",
                    recScoreThresh = 0.24f,
                    recBatchSize = 12,
                ),
                engineConfig = EngineConfig(
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
                    executionProvider = OCRExecutionProvider.AUTO,
                    allowFp16 = true,
                ),
                detModelAssetPath = "models/det/inference.onnx",
                recModelAssetPath = "models/rec/inference.onnx",
                recConfigAssetPath = "models/rec/inference.yml",
            )
        }
    }

    suspend fun process(bitmap: Bitmap, screenWidth: Int, screenHeight: Int): OcrOutput {
        val engine = ocr ?: return OcrOutput(emptyList(), emptyList(), 0, 0, 0, modelLabel)
        val run = engine.recognize(bitmap)
        val rawRegions = run.results.asSequence()
            .filter { it.confidence >= 0.24f }
            .map { result ->
                val text = normalize(result.text)
                OcrRegion(
                    text = text,
                    bounds = result.toScreenBounds(bitmap, screenWidth, screenHeight),
                    confidence = result.confidence,
                    backgroundColor = if (containsJapanese(text)) sampleBackground(bitmap, result) else Color.TRANSPARENT,
                )
            }
            .filter { it.text.isNotBlank() }
            .filterNot { it.bounds.left < 430f && it.bounds.top < 100f }
            .take(80)
            .toList()

        val grouped = VerticalTextGrouper.merge(rawRegions)
        val outputItems = ArrayList<OverlayItem>(grouped.size)
        val pending = ArrayList<PendingTranslation>()
        var japaneseCount = 0

        grouped.forEachIndexed { index, region ->
            val id = itemId(region, index)
            if (!containsJapanese(region.text)) {
                outputItems += OverlayItem(region.bounds, kind = OverlayKind.HIGHLIGHT, id = id)
                return@forEachIndexed
            }

            japaneseCount++
            val cached = synchronized(translations) { translations[region.text] }
            val base = OverlayItem(
                bounds = region.bounds,
                text = cached,
                kind = OverlayKind.TRANSLATED,
                backgroundColor = region.backgroundColor,
                foregroundColor = contrastingColor(region.backgroundColor),
                id = id,
            )
            outputItems += base
            if (cached == null) pending += PendingTranslation(region.text, base)
        }

        return OcrOutput(
            items = outputItems,
            pendingTranslations = pending,
            detectedCount = grouped.size,
            japaneseCount = japaneseCount,
            elapsedMs = run.totalTimeMs,
            modelLabel = modelLabel,
        )
    }

    suspend fun translatePending(pending: List<PendingTranslation>): List<OverlayItem> {
        if (pending.isEmpty() || !translationReady.await()) return emptyList()
        return coroutineScope {
            pending.distinctBy { it.source }.map { work ->
                async(Dispatchers.IO) {
                    val english = runCatching { translate(work.source) }.getOrDefault("")
                    if (english.isBlank()) null else work.item.copy(text = english)
                }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun close() {
        translationScope.cancel()
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

    private fun itemId(region: OcrRegion, index: Int): String {
        val box = region.bounds
        return "${region.text.hashCode()}:$index:${(box.left / 8).toInt()}:${(box.top / 8).toInt()}"
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
        val margin = max(2, max(right - left, bottom - top) / 14)
        val samples = IntArray(32)
        var count = 0
        for (step in 0 until 8) {
            val x = left + (right - left) * step / 7
            val y = top + (bottom - top) * step / 7
            samples[count++] = bitmap.getPixel(x, (top - margin).coerceAtLeast(0))
            samples[count++] = bitmap.getPixel(x, (bottom + margin).coerceAtMost(bitmap.height - 1))
            samples[count++] = bitmap.getPixel((left - margin).coerceAtLeast(0), y)
            samples[count++] = bitmap.getPixel((right + margin).coerceAtMost(bitmap.width - 1), y)
        }
        val red = samples.map { Color.red(it) }.sorted()[count / 2]
        val green = samples.map { Color.green(it) }.sorted()[count / 2]
        val blue = samples.map { Color.blue(it) }.sorted()[count / 2]
        return Color.rgb(red, green, blue)
    }

    private fun contrastingColor(background: Int): Int {
        val luminance = Color.red(background) * 0.299 + Color.green(background) * 0.587 + Color.blue(background) * 0.114
        return if (luminance > 145) Color.BLACK else Color.WHITE
    }
}
