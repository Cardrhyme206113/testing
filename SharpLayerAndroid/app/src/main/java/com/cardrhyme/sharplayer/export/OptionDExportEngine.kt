package com.cardrhyme.sharplayer.export

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.cardrhyme.sharplayer.codec.FastStructureAnalyzer
import com.cardrhyme.sharplayer.codec.StructureCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class OptionDExportEngine(private val context: Context) {
    data class Settings(
        val totalBitrateKbps: Int = 300,
        val outputHeight: Int = 1080,
    )

    data class Update(
        val progress: Float,
        val stage: String,
        val detail: String,
    )

    data class Result(
        val file: File,
        val actualTotalKbps: Int,
        val requestedTotalKbps: Int,
        val requestedVideoKbps: Int,
        val audioKbps: Int,
        val structureKbps: Int,
        val attempts: Int,
        val metadata: JSONObject,
    )

    @Volatile
    private var cancelled = false

    @Volatile
    private var activeTransformer: Transformer? = null

    fun cancel() {
        cancelled = true
        activeTransformer?.cancel()
        activeTransformer = null
    }

    suspend fun export(
        source: Uri,
        settings: Settings,
        onUpdate: (Update) -> Unit,
    ): Result {
        cancelled = false
        val target = settings.totalBitrateKbps.coerceIn(120, 4_000)
        val structureBudget = (target * 0.14f).roundToInt().coerceIn(10, 84)

        onUpdate(
            Update(
                0.01f,
                "Fast structure analysis",
                "5 Hz deltas · 1 Hz depth · 0.5 Hz segmentation · GPU preferred",
            )
        )
        val analysis = FastStructureAnalyzer.analyze(
            context = context,
            source = source,
            structureBudgetKbps = structureBudget,
            onProgress = { p, detail ->
                onUpdate(Update(0.02f + p * 0.40f, "Fast structure analysis", detail))
            },
            isCancelled = { cancelled },
        )
        ensureNotCancelled()

        val audioKbps = when {
            target <= 220 -> 16
            target <= 450 -> 24
            target <= 900 -> 32
            else -> 48
        }
        val containerReserve = max(6, (target * 0.025f).roundToInt())
        var requestedVideoKbps = target - audioKbps - analysis.bitrateKbps - containerReserve
        require(requestedVideoKbps >= 40) {
            "The ${target} kbps target is below the ${analysis.bitrateKbps} kbps structure layer plus audio overhead."
        }

        val session = System.currentTimeMillis()
        val workDir = File(context.cacheDir, "sharp-option-d-$session").apply { mkdirs() }
        var lastMeasured = Int.MAX_VALUE
        var attempt = 0
        try {
            while (attempt < 4) {
                ensureNotCancelled()
                attempt++
                val base = File(workDir, "base-$attempt.mp4")
                val packed = File(workDir, "packed-$attempt.mp4")
                base.delete()
                packed.delete()

                onUpdate(
                    Update(
                        0.43f,
                        "Forced CBR encode",
                        "Attempt $attempt · ${settings.outputHeight}p H.264 at $requestedVideoKbps kbps",
                    )
                )
                transcodeBase(
                    source = source,
                    output = base,
                    height = settings.outputHeight,
                    videoBitrate = requestedVideoKbps * 1_000,
                    audioBitrate = audioKbps * 1_000,
                    attempt = attempt,
                    onUpdate = onUpdate,
                )
                ensureNotCancelled()
                require(base.exists() && base.length() > 0L) { "The hardware encoder produced an empty base MP4." }

                val metadata = JSONObject()
                    .put("format", "SharpLayer Option D Fast")
                    .put("version", 3)
                    .put("updateFps", analysis.sequence.fps)
                    .put("depthRefreshFps", FastStructureAnalyzer.DEPTH_REFRESH_FPS.toDouble())
                    .put("segmentationRefreshFps", FastStructureAnalyzer.SEGMENTATION_REFRESH_FPS.toDouble())
                    .put("depthModel", "MiDaS v2.1 mobile")
                    .put("segmentationModel", "DeepLab-v3")
                    .put("inference", "GPU delegate preferred, CPU fallback")
                    .put("temporalPropagation", "global motion warp")
                    .put("lineart", "multiscale-gradient")
                    .put("requestedTotalKbps", target)
                    .put("requestedVideoKbps", requestedVideoKbps)
                    .put("audioKbps", audioKbps)
                    .put("structureKbps", analysis.bitrateKbps)
                    .put("outputHeight", settings.outputHeight)
                    .put("analysisWidth", analysis.sequence.width)
                    .put("analysisHeight", analysis.sequence.height)
                    .put("activePixelPercent", analysis.activePixelPercent.toDouble())
                    .put("changedPixelPercent", analysis.changedPixelPercent.toDouble())
                    .put("attempts", attempt)

                val durationSeconds = analysis.sequence.durationMs.coerceAtLeast(1L) / 1_000.0
                val estimatedBytes = base.length() + analysis.encoded.size + metadata.toString().toByteArray().size + 64L
                val estimatedKbps = ceil(estimatedBytes * 8.0 / durationSeconds / 1_000.0).toInt()
                metadata.put("actualTotalKbps", estimatedKbps)

                onUpdate(Update(0.88f, "Packing", "Appending the 5 Hz relative structure stream"))
                withContext(Dispatchers.IO) {
                    StructureCodec.pack(base, analysis.encoded, packed, metadata) { percent ->
                        onUpdate(Update(0.88f + percent / 100f * 0.06f, "Packing", "$percent%"))
                    }
                }
                ensureNotCancelled()

                var measured = ceil(packed.length() * 8.0 / durationSeconds / 1_000.0).toInt()
                if (measured != estimatedKbps) {
                    metadata.put("actualTotalKbps", measured)
                    packed.delete()
                    withContext(Dispatchers.IO) {
                        StructureCodec.pack(base, analysis.encoded, packed, metadata)
                    }
                    measured = ceil(packed.length() * 8.0 / durationSeconds / 1_000.0).toInt()
                }
                lastMeasured = measured

                if (measured <= ceil(target * 1.04).toInt()) {
                    val finalFile = File(context.cacheDir, "SharpLayer-OptionD-Fast-$session.mp4")
                    finalFile.delete()
                    withContext(Dispatchers.IO) { packed.copyTo(finalFile, overwrite = true) }
                    onUpdate(
                        Update(
                            1f,
                            "Done",
                            "Measured $measured kbps total · structure ${analysis.bitrateKbps} kbps · $attempt attempt(s)",
                        )
                    )
                    return Result(
                        file = finalFile,
                        actualTotalKbps = measured,
                        requestedTotalKbps = target,
                        requestedVideoKbps = requestedVideoKbps,
                        audioKbps = audioKbps,
                        structureKbps = analysis.bitrateKbps,
                        attempts = attempt,
                        metadata = metadata,
                    )
                }

                val ratio = target.toDouble() / measured.toDouble()
                val next = (requestedVideoKbps * ratio * 0.90).roundToInt().coerceAtLeast(40)
                if (next >= requestedVideoKbps || requestedVideoKbps <= 40) break
                onUpdate(
                    Update(
                        0.43f,
                        "Bitrate correction",
                        "Measured $measured kbps, above $target. Retrying at $next kbps video.",
                    )
                )
                requestedVideoKbps = next
            }
            error(
                "This device's encoder would not stay under the forced $target kbps ceiling " +
                    "(last measured $lastMeasured kbps). No misleading export was saved."
            )
        } finally {
            activeTransformer = null
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
        }
    }

    private suspend fun transcodeBase(
        source: Uri,
        output: File,
        height: Int,
        videoBitrate: Int,
        audioBitrate: Int,
        attempt: Int,
        onUpdate: (Update) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val videoSettings = VideoEncoderSettings.Builder()
                .setBitrate(videoBitrate)
                .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                .build()
            val audioSettings = AudioEncoderSettings.Builder()
                .setBitrate(audioBitrate)
                .build()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(videoSettings)
                .setRequestedAudioEncoderSettings(audioSettings)
                .build()
            val effects = Effects(
                emptyList(),
                listOf<Effect>(Presentation.createForHeight(height)),
            )
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(effects)
                .build()

            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            lateinit var poll: Runnable
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        activeTransformer = null
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        handler.removeCallbacks(poll)
                        activeTransformer = null
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
            activeTransformer = transformer

            poll = object : Runnable {
                override fun run() {
                    if (!continuation.isActive) return
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val progress = holder.progress.coerceIn(0, 100)
                        onUpdate(
                            Update(
                                0.43f + progress / 100f * 0.43f,
                                "Forced CBR encode",
                                "Attempt $attempt · $progress%",
                            )
                        )
                    }
                    handler.postDelayed(this, 250L)
                }
            }

            continuation.invokeOnCancellation {
                handler.removeCallbacks(poll)
                transformer.cancel()
                activeTransformer = null
            }
            try {
                transformer.start(edited, output.absolutePath)
                handler.post(poll)
            } catch (t: Throwable) {
                handler.removeCallbacks(poll)
                activeTransformer = null
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }
    }

    private fun ensureNotCancelled() {
        if (cancelled) throw kotlinx.coroutines.CancellationException("Cancelled")
    }
}
