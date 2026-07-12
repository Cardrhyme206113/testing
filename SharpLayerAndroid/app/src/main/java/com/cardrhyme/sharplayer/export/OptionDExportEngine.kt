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

    private data class EncoderPlan(
        val label: String,
        val frameRate: Int,
        val bitrateMode: Int?,
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
        val audioKbps = when {
            target <= 220 -> 16
            target <= 450 -> 24
            target <= 900 -> 32
            else -> 48
        }
        val containerReserve = max(6, (target * 0.025f).roundToInt())
        val probeVideoKbps = (target - audioKbps - structureBudget - containerReserve).coerceAtLeast(40)

        val session = System.currentTimeMillis()
        val workDir = File(context.cacheDir, "sharp-option-d-$session").apply { mkdirs() }

        try {
            // Check the phone's real MediaCodec behavior before running any models.
            val encoderPlan = preflightEncoder(
                source = source,
                workDir = workDir,
                height = settings.outputHeight,
                videoBitrate = probeVideoKbps * 1_000,
                audioBitrate = audioKbps * 1_000,
                onUpdate = onUpdate,
            )
            ensureNotCancelled()

            onUpdate(
                Update(
                    0.035f,
                    "Fast structure analysis",
                    "Encoder passed (${encoderPlan.frameRate} fps ${encoderPlan.label}); starting models",
                )
            )
            val analysis = FastStructureAnalyzer.analyze(
                context = context,
                source = source,
                structureBudgetKbps = structureBudget,
                onProgress = { p, detail ->
                    onUpdate(Update(0.04f + p * 0.38f, "Fast structure analysis", detail))
                },
                isCancelled = { cancelled },
            )
            ensureNotCancelled()

            var requestedVideoKbps = target - audioKbps - analysis.bitrateKbps - containerReserve
            require(requestedVideoKbps >= 40) {
                "The $target kbps target is below the ${analysis.bitrateKbps} kbps structure layer plus audio overhead."
            }

            var lastMeasured = Int.MAX_VALUE
            var attempt = 0
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
                        "Low-bitrate encode",
                        "Attempt $attempt · ${settings.outputHeight}p · ${encoderPlan.frameRate} fps · " +
                            "${encoderPlan.label} · $requestedVideoKbps kbps video",
                    )
                )
                transcodeBase(
                    source = source,
                    output = base,
                    height = settings.outputHeight,
                    videoBitrate = requestedVideoKbps * 1_000,
                    audioBitrate = audioKbps * 1_000,
                    plan = encoderPlan,
                    attempt = attempt,
                    clipEndMs = null,
                    reportProgress = true,
                    onUpdate = onUpdate,
                )
                ensureNotCancelled()
                require(base.exists() && base.length() > 0L) {
                    "The hardware encoder produced an empty base MP4."
                }

                val metadata = JSONObject()
                    .put("format", "SharpLayer Option D Fast")
                    .put("version", 4)
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
                    .put("outputFrameRate", encoderPlan.frameRate)
                    .put("encoderMode", encoderPlan.label)
                    .put("analysisWidth", analysis.sequence.width)
                    .put("analysisHeight", analysis.sequence.height)
                    .put("activePixelPercent", analysis.activePixelPercent.toDouble())
                    .put("changedPixelPercent", analysis.changedPixelPercent.toDouble())
                    .put("attempts", attempt)

                val durationSeconds = analysis.sequence.durationMs.coerceAtLeast(1L) / 1_000.0
                val estimatedBytes =
                    base.length() + analysis.encoded.size + metadata.toString().toByteArray().size + 64L
                val estimatedKbps = ceil(estimatedBytes * 8.0 / durationSeconds / 1_000.0).toInt()
                metadata.put("actualTotalKbps", estimatedKbps)

                onUpdate(
                    Update(
                        0.88f,
                        "Packing",
                        "Appending the ${analysis.sequence.fps} Hz relative structure stream",
                    )
                )
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
                            "Measured $measured kbps total · ${encoderPlan.frameRate} fps · " +
                                "structure ${analysis.bitrateKbps} kbps · $attempt attempt(s)",
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

    private suspend fun preflightEncoder(
        source: Uri,
        workDir: File,
        height: Int,
        videoBitrate: Int,
        audioBitrate: Int,
        onUpdate: (Update) -> Unit,
    ): EncoderPlan {
        val candidates = listOf(
            EncoderPlan(
                label = "CBR",
                frameRate = 30,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            ),
            EncoderPlan(
                label = "VBR",
                frameRate = 30,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            ),
            EncoderPlan(
                label = "device default",
                frameRate = 30,
                bitrateMode = null,
            ),
            EncoderPlan(
                label = "VBR compatibility",
                frameRate = 24,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            ),
        )

        var lastFailure: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            ensureNotCancelled()
            val probe = File(workDir, "encoder-probe-$index.mp4")
            probe.delete()
            onUpdate(
                Update(
                    0.005f + index * 0.006f,
                    "Encoder preflight",
                    "Testing ${candidate.frameRate} fps ${candidate.label} before neural analysis…",
                )
            )
            try {
                transcodeBase(
                    source = source,
                    output = probe,
                    height = height,
                    videoBitrate = videoBitrate,
                    audioBitrate = audioBitrate,
                    plan = candidate,
                    attempt = 0,
                    clipEndMs = 700L,
                    reportProgress = false,
                    onUpdate = onUpdate,
                )
                if (probe.exists() && probe.length() > 0L) {
                    probe.delete()
                    onUpdate(
                        Update(
                            0.03f,
                            "Encoder preflight",
                            "Passed: ${candidate.frameRate} fps ${candidate.label}",
                        )
                    )
                    return candidate
                }
            } catch (t: Throwable) {
                lastFailure = t
            } finally {
                probe.delete()
            }
        }

        throw IllegalStateException(
            "This phone rejected every tested low-bitrate H.264 configuration " +
                "(30 fps CBR, 30 fps VBR, device-default, and 24 fps VBR). " +
                "The check stopped before depth/segmentation analysis.",
            lastFailure,
        )
    }

    private suspend fun transcodeBase(
        source: Uri,
        output: File,
        height: Int,
        videoBitrate: Int,
        audioBitrate: Int,
        plan: EncoderPlan,
        attempt: Int,
        clipEndMs: Long?,
        reportProgress: Boolean,
        onUpdate: (Update) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val videoSettingsBuilder = VideoEncoderSettings.Builder()
                .setBitrate(videoBitrate)
            plan.bitrateMode?.let { videoSettingsBuilder.setBitrateMode(it) }
            val videoSettings = videoSettingsBuilder.build()

            val audioSettings = AudioEncoderSettings.Builder()
                .setBitrate(audioBitrate)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setEnableFallback(true)
                .setEnableFormatFallback(true)
                .setRequestedVideoEncoderSettings(videoSettings)
                .setRequestedAudioEncoderSettings(audioSettings)
                .build()

            val effects = Effects(
                emptyList(),
                listOf<Effect>(Presentation.createForHeight(height)),
            )

            val mediaItemBuilder = MediaItem.Builder().setUri(source)
            if (clipEndMs != null) {
                mediaItemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(clipEndMs)
                        .build()
                )
            }

            val edited = EditedMediaItem.Builder(mediaItemBuilder.build())
                .setEffects(effects)
                .setFrameRate(plan.frameRate)
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
                    if (reportProgress) {
                        val state = transformer.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val encodeProgress = holder.progress.coerceIn(0, 100)
                            onUpdate(
                                Update(
                                    0.43f + encodeProgress / 100f * 0.43f,
                                    "Low-bitrate encode",
                                    "Attempt $attempt · $encodeProgress% · ${plan.frameRate} fps ${plan.label}",
                                )
                            )
                        }
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
                output.delete()
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
