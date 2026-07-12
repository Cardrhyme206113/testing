package com.cardrhyme.shrink

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class HardwareVideoCompressor(private val context: Context) {
    data class Request(
        val source: android.net.Uri,
        val requestedHeight: Int,
        val videoBitrateMbps: Float,
        val requestedFps: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val sourceFps: Float,
        val clipStartMs: Long? = null,
        val clipDurationMs: Long? = null,
        val removeAudio: Boolean = false,
    )

    data class Result(
        val file: File,
        val codecLabel: String,
        val effectiveHeight: Int,
        val effectiveFps: Int,
    )

    @Volatile
    private var activeTransformer: Transformer? = null

    fun cancel() {
        activeTransformer?.cancel()
        activeTransformer = null
    }

    suspend fun export(
        request: Request,
        output: File,
        onProgress: (Int, String) -> Unit,
    ): Result {
        val effectiveHeight = if (request.sourceHeight > 0) {
            min(request.requestedHeight, request.sourceHeight)
        } else {
            request.requestedHeight
        }.coerceAtLeast(2)

        val effectiveFps = if (request.sourceFps > 1f) {
            min(request.requestedFps.toFloat(), request.sourceFps + 0.5f).roundToInt().coerceAtLeast(1)
        } else {
            request.requestedFps
        }

        val targetWidth = if (request.sourceWidth > 0 && request.sourceHeight > 0) {
            ((effectiveHeight * request.sourceWidth.toDouble() / request.sourceHeight.toDouble())
                .roundToInt().coerceAtLeast(2) / 2) * 2
        } else {
            1920
        }

        val preferredMime = chooseHardwareMime(targetWidth, effectiveHeight, effectiveFps)
        val attempts = if (preferredMime == MimeTypes.VIDEO_H265) {
            listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264)
        } else {
            listOf(MimeTypes.VIDEO_H264)
        }

        var lastError: Throwable? = null
        for ((index, mime) in attempts.withIndex()) {
            try {
                if (index > 0) onProgress(0, "HEVC was rejected; retrying with H.264 hardware")
                output.delete()
                transcode(
                    request = request,
                    output = output,
                    targetHeight = effectiveHeight,
                    effectiveFps = effectiveFps,
                    videoMime = mime,
                    onProgress = onProgress,
                )
                require(output.exists() && output.length() > 0L) {
                    "The hardware encoder produced an empty output file."
                }
                return Result(
                    file = output,
                    codecLabel = if (mime == MimeTypes.VIDEO_H265) "HEVC hardware" else "H.264 hardware",
                    effectiveHeight = effectiveHeight,
                    effectiveFps = effectiveFps,
                )
            } catch (t: Throwable) {
                lastError = t
                output.delete()
            }
        }
        throw IllegalStateException("This phone rejected the requested hardware encode settings.", lastError)
    }

    private suspend fun transcode(
        request: Request,
        output: File,
        targetHeight: Int,
        effectiveFps: Int,
        videoMime: String,
        onProgress: (Int, String) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val videoSettings = VideoEncoderSettings.Builder()
                .setBitrate((request.videoBitrateMbps * 1_000_000f).roundToInt())
                .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                .build()
            val audioSettings = AudioEncoderSettings.Builder()
                .setBitrate(192_000)
                .build()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(videoSettings)
                .setRequestedAudioEncoderSettings(audioSettings)
                .build()

            val videoEffects = listOf<Effect>(
                Presentation.createForHeight(targetHeight),
                FrameDropEffect.createDefaultFrameDropEffect(effectiveFps.toFloat()),
            )
            val effects = Effects(emptyList(), videoEffects)

            val mediaItemBuilder = MediaItem.Builder().setUri(request.source)
            if (request.clipStartMs != null || request.clipDurationMs != null) {
                val start = request.clipStartMs?.coerceAtLeast(0L) ?: 0L
                val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
                request.clipDurationMs?.let { duration ->
                    clipping.setEndPositionMs(start + duration.coerceAtLeast(1L))
                }
                mediaItemBuilder.setClippingConfiguration(clipping.build())
            }

            val edited = EditedMediaItem.Builder(mediaItemBuilder.build())
                .setEffects(effects)
                .setRemoveAudio(request.removeAudio)
                .build()

            val handler = Handler(Looper.getMainLooper())
            val progressHolder = ProgressHolder()
            lateinit var poll: Runnable
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(videoMime)
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
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val value = progressHolder.progress.coerceIn(0, 100)
                        onProgress(value, "Encoding with ${if (videoMime == MimeTypes.VIDEO_H265) "HEVC" else "H.264"} hardware")
                    }
                    handler.postDelayed(this, 180L)
                }
            }

            continuation.invokeOnCancellation {
                handler.removeCallbacks(poll)
                transformer.cancel()
                activeTransformer = null
            }

            try {
                output.parentFile?.mkdirs()
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

    private fun chooseHardwareMime(width: Int, height: Int, fps: Int): String {
        return if (hasHardwareEncoder(MimeTypes.VIDEO_H265, width, height, fps)) {
            MimeTypes.VIDEO_H265
        } else {
            MimeTypes.VIDEO_H264
        }
    }

    private fun hasHardwareEncoder(mime: String, width: Int, height: Int, fps: Int): Boolean {
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                if (!info.isEncoder || !info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
                    return@any false
                }
                val hardware = if (Build.VERSION.SDK_INT >= 29) {
                    info.isHardwareAccelerated
                } else {
                    val name = info.name.lowercase()
                    !name.contains("google") && !name.contains("android") && !name.contains("software") && !name.contains("sw")
                }
                if (!hardware) return@any false
                runCatching {
                    val videoCaps = info.getCapabilitiesForType(mime).videoCapabilities
                    videoCaps == null || videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }
}
