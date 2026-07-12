package com.cardrhyme.shrink

import android.app.Application
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class ShrinkViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val sourceUri: Uri? = null,
        val playbackUri: Uri? = null,
        val sourceName: String = "",
        val sourceWidth: Int = 0,
        val sourceHeight: Int = 0,
        val sourceFps: Float = 0f,
        val durationMs: Long = 0L,
        val sourceSizeBytes: Long = 0L,
        val positionMs: Long = 0L,
        val resolutionIndex: Int = 2,
        val bitrateMbps: Float = 10f,
        val fpsIndex: Int = 2,
        val isWorking: Boolean = false,
        val progress: Int = 0,
        val status: String = "",
        val errorMessage: String? = null,
        val successMessage: String? = null,
        val lastCodec: String = "Hardware encoder",
    )

    companion object {
        val RESOLUTION_LABELS = listOf("720p", "960p", "1080p", "1440p", "4K")
        val RESOLUTION_HEIGHTS = listOf(720, 960, 1080, 1440, 2160)
        val FPS_VALUES = listOf(24, 30, 60)
    }

    private val app = getApplication<Application>()
    private val compressor = HardwareVideoCompressor(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var activeJob: Job? = null
    private var previewFile: File? = null

    fun selectSource(uri: Uri) {
        activeJob?.cancel()
        compressor.cancel()
        activeJob = viewModelScope.launch {
            runCatching { readMetadata(uri) }
                .onSuccess { info ->
                    previewFile?.delete()
                    previewFile = null
                    _state.value = UiState(
                        sourceUri = uri,
                        playbackUri = uri,
                        sourceName = info.name,
                        sourceWidth = info.width,
                        sourceHeight = info.height,
                        sourceFps = info.fps,
                        durationMs = info.durationMs,
                        sourceSizeBytes = info.sizeBytes,
                        positionMs = max(0L, info.durationMs / 2L - 500L),
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(errorMessage = readableError(error))
                }
        }
    }

    fun setPosition(ms: Long) {
        val current = _state.value
        _state.value = current.copy(
            positionMs = ms.coerceIn(0L, max(0L, current.durationMs - 1_000L)),
            playbackUri = current.sourceUri,
        )
    }

    fun setResolution(index: Int) {
        val current = _state.value
        _state.value = current.copy(
            resolutionIndex = index.coerceIn(0, RESOLUTION_HEIGHTS.lastIndex),
            playbackUri = current.sourceUri,
        )
    }

    fun setBitrate(value: Float) {
        val snapped = (kotlin.math.round(value / 2.5f) * 2.5f).coerceIn(2.5f, 25f)
        val current = _state.value
        _state.value = current.copy(bitrateMbps = snapped, playbackUri = current.sourceUri)
    }

    fun setFps(index: Int) {
        val current = _state.value
        _state.value = current.copy(
            fpsIndex = index.coerceIn(0, FPS_VALUES.lastIndex),
            playbackUri = current.sourceUri,
        )
    }

    fun createPreview() {
        val current = _state.value
        val source = current.sourceUri ?: return
        startWork("Preparing one-second preview") {
            val file = File(app.cacheDir, "shrink-preview-${System.currentTimeMillis()}.mp4")
            val result = compressor.export(
                request = requestFrom(current, source).copy(
                    clipStartMs = current.positionMs,
                    clipDurationMs = 1_000L,
                    removeAudio = true,
                ),
                output = file,
                onProgress = { progress, detail ->
                    _state.value = _state.value.copy(progress = progress, status = detail)
                },
            )
            previewFile?.takeIf { it != file }?.delete()
            previewFile = file
            _state.value = _state.value.copy(
                playbackUri = Uri.fromFile(file),
                lastCodec = result.codecLabel,
                successMessage = "One-second ${result.codecLabel} preview ready",
            )
        }
    }

    fun compressFullVideo() {
        val current = _state.value
        val source = current.sourceUri ?: return
        startWork("Starting hardware compression") {
            val workDir = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "work").apply { mkdirs() }
            val temp = File(workDir, "shrink-${System.currentTimeMillis()}.mp4")
            try {
                val result = compressor.export(
                    request = requestFrom(current, source),
                    output = temp,
                    onProgress = { progress, detail ->
                        _state.value = _state.value.copy(progress = progress, status = detail)
                    },
                )
                _state.value = _state.value.copy(progress = 100, status = "Saving to Movies/Shrink")
                val published = publishVideo(temp, current, result.codecLabel)
                _state.value = _state.value.copy(
                    playbackUri = published,
                    lastCodec = result.codecLabel,
                    successMessage = "Saved to Movies/Shrink using ${result.codecLabel}",
                )
            } finally {
                temp.delete()
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        compressor.cancel()
        _state.value = _state.value.copy(isWorking = false, progress = 0, status = "")
    }

    fun clearMessage() {
        _state.value = _state.value.copy(errorMessage = null, successMessage = null)
    }

    private fun startWork(initialStatus: String, block: suspend () -> Unit) {
        if (_state.value.isWorking) return
        activeJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isWorking = true,
                progress = 0,
                status = initialStatus,
                errorMessage = null,
                successMessage = null,
            )
            try {
                block()
            } catch (_: CancellationException) {
                // User cancellation is already reflected by cancel().
            } catch (error: Throwable) {
                _state.value = _state.value.copy(errorMessage = readableError(error))
            } finally {
                _state.value = _state.value.copy(isWorking = false)
                activeJob = null
            }
        }
    }

    private fun requestFrom(current: UiState, source: Uri) = HardwareVideoCompressor.Request(
        source = source,
        requestedHeight = RESOLUTION_HEIGHTS[current.resolutionIndex],
        videoBitrateMbps = current.bitrateMbps,
        requestedFps = FPS_VALUES[current.fpsIndex],
        sourceWidth = current.sourceWidth,
        sourceHeight = current.sourceHeight,
        sourceFps = current.sourceFps,
    )

    private data class Metadata(
        val name: String,
        val width: Int,
        val height: Int,
        val fps: Float,
        val durationMs: Long,
        val sizeBytes: Long,
    )

    private suspend fun readMetadata(uri: Uri): Metadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L)
                ?: error("Could not read the video duration.")
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val swap = width
                width = height
                height = swap
            }
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
            val size = app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.coerceAtLeast(0L)
            } ?: 0L
            val name = app.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "Video"
            Metadata(name, width, height, fps, duration, size)
        } finally {
            retriever.release()
        }
    }

    private suspend fun publishVideo(
        source: File,
        state: UiState,
        codecLabel: String,
    ): Uri = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val bitrate = if (state.bitrateMbps % 1f == 0f) state.bitrateMbps.toInt().toString() else state.bitrateMbps.toString()
        val name = "Shrink_${stamp}_${RESOLUTION_LABELS[state.resolutionIndex]}_${bitrate}Mbps_${FPS_VALUES[state.fpsIndex]}fps.mp4"

        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Shrink")
                put(MediaStore.Video.Media.IS_PENDING, 1)
                put(MediaStore.Video.Media.DESCRIPTION, "Compressed with $codecLabel")
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the output video in MediaStore.")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().buffered(1024 * 1024).use { input ->
                        input.copyTo(output, 1024 * 1024)
                    }
                } ?: error("Could not open the output video for writing.")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val directory = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Shrink").apply { mkdirs() }
            val output = File(directory, name)
            source.copyTo(output, overwrite = true)
            Uri.fromFile(output)
        }
    }

    private fun readableError(error: Throwable): String {
        var root = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
    }

    override fun onCleared() {
        compressor.cancel()
        previewFile?.delete()
        super.onCleared()
    }
}
