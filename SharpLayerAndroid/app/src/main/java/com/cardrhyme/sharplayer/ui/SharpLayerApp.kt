package com.cardrhyme.sharplayer.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
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
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

private enum class Screen { EXPORT, PLAYER }

@OptIn(UnstableApi::class)
@Composable
fun SharpLayerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.EXPORT) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var playerUri by remember { mutableStateOf<Uri?>(null) }
    var lastExportUri by remember { mutableStateOf<Uri?>(null) }

    var targetHeight by remember { mutableIntStateOf(720) }
    var videoBitrateKbps by remember { mutableIntStateOf(1_200) }

    var status by remember { mutableStateOf("Choose a source video.") }
    var progress by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    var activeTemp by remember { mutableStateOf<File?>(null) }
    var pendingEncodedFile by remember { mutableStateOf<File?>(null) }
    var saveRequestToken by remember { mutableIntStateOf(0) }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        source = uri
        if (uri != null) {
            status = "Source selected: ${uri.lastPathSegment ?: "video"}"
            progress = 0f
        }
    }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destination ->
        val encoded = pendingEncodedFile
        if (destination == null) {
            status = if (encoded != null) {
                "Encoding finished. Tap Save encoded file when ready."
            } else {
                "Save cancelled."
            }
            return@rememberLauncherForActivityResult
        }
        if (encoded == null || !encoded.exists() || encoded.length() <= 0L) {
            status = "Save failed: encoded temporary file is missing or empty."
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            running = true
            progress = 0.94f
            status = "Writing ${formatBytes(encoded.length())} to the selected file…"
            try {
                val written = withContext(Dispatchers.IO) {
                    writeFileToUri(context, encoded, destination)
                }
                require(written > 0L) { "The destination received zero bytes." }

                lastExportUri = destination
                pendingEncodedFile = null
                encoded.delete()
                progress = 1f
                status = "Saved ${formatBytes(written)}. Open it manually from the Player tab."
            } catch (t: Throwable) {
                status = "Save failed: ${t.message ?: t::class.java.simpleName}. The encoded cache file is still available to retry."
            } finally {
                running = false
            }
        }
    }

    val playerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        playerUri = uri
    }

    LaunchedEffect(saveRequestToken) {
        if (saveRequestToken > 0 && pendingEncodedFile != null) {
            savePicker.launch("SharpLayer-${targetHeight}p-${videoBitrateKbps}kbps.mp4")
        }
    }

    LaunchedEffect(running, transformer) {
        val active = transformer ?: return@LaunchedEffect
        if (!running) return@LaunchedEffect
        val holder = ProgressHolder()
        while (true) {
            val state = active.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                progress = (holder.progress.coerceIn(0, 100) / 100f) * 0.88f
                status = "Encoding H.264/AAC… ${holder.progress}%"
            }
            delay(250)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            transformer?.cancel()
            activeTemp?.delete()
            pendingEncodedFile?.delete()
        }
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("SharpLayer", style = MaterialTheme.typography.headlineMedium)
                    Text("Hardware compression and separate playback", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.EXPORT,
                    onClick = { screen = Screen.EXPORT },
                    icon = { Text("⇩") },
                    label = { Text("Export") }
                )
                NavigationBarItem(
                    selected = screen == Screen.PLAYER,
                    onClick = { screen = Screen.PLAYER },
                    icon = { Text("▶") },
                    label = { Text("Player") }
                )
            }
        }
    ) { padding ->
        when (screen) {
            Screen.EXPORT -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Source", style = MaterialTheme.typography.titleMedium)
                        Text(source?.lastPathSegment ?: "No source selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick = { sourcePicker.launch(arrayOf("video/*")) },
                            enabled = !running
                        ) { Text("Choose video") }
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Real compression settings", style = MaterialTheme.typography.titleMedium)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Output height")
                            Text("${targetHeight}p", color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = targetHeight.toFloat(),
                            onValueChange = {
                                targetHeight = ((it / 120f).roundToInt() * 120).coerceIn(360, 1080)
                            },
                            valueRange = 360f..1080f,
                            steps = 5,
                            enabled = !running
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Video bitrate")
                            Text("${videoBitrateKbps} kbps", color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = videoBitrateKbps.toFloat(),
                            onValueChange = {
                                videoBitrateKbps = ((it / 50f).roundToInt() * 50).coerceIn(300, 4_000)
                            },
                            valueRange = 300f..4_000f,
                            steps = 73,
                            enabled = !running
                        )
                        Text(
                            "Audio is re-encoded to AAC at 64 kbps. Scaling and an explicit encoder bitrate force a real transcode instead of copying the input stream.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = source != null && !running,
                                onClick = {
                                    val input = source ?: return@Button
                                    transformer?.cancel()
                                    activeTemp?.delete()
                                    pendingEncodedFile?.delete()
                                    pendingEncodedFile = null

                                    val temp = File(context.cacheDir, "sharplayer-${System.currentTimeMillis()}.mp4")
                                    if (temp.exists()) temp.delete()
                                    activeTemp = temp
                                    running = true
                                    progress = 0.01f
                                    status = "Starting hardware encoder at ${targetHeight}p / ${videoBitrateKbps} kbps…"

                                    val videoSettings = VideoEncoderSettings.Builder()
                                        .setBitrate(videoBitrateKbps * 1_000)
                                        .build()
                                    val audioSettings = AudioEncoderSettings.Builder()
                                        .setBitrate(64_000)
                                        .build()
                                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                                        .setRequestedVideoEncoderSettings(videoSettings)
                                        .setRequestedAudioEncoderSettings(audioSettings)
                                        .build()

                                    val videoEffects: List<Effect> = listOf(Presentation.createForHeight(targetHeight))
                                    val effects = Effects(emptyList(), videoEffects)
                                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
                                        .setEffects(effects)
                                        .build()

                                    val built = Transformer.Builder(context)
                                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                                        .setEncoderFactory(encoderFactory)
                                        .addListener(object : Transformer.Listener {
                                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                                transformer = null
                                                running = false
                                                activeTemp = null

                                                if (!temp.exists() || temp.length() <= 0L) {
                                                    temp.delete()
                                                    progress = 0f
                                                    status = "Encoding failed: Media3 reported completion but produced an empty file."
                                                    return
                                                }

                                                pendingEncodedFile = temp
                                                progress = 0.92f
                                                status = "Encoded ${formatBytes(temp.length())}. Choose where to save it."
                                                saveRequestToken += 1
                                            }

                                            override fun onError(
                                                composition: Composition,
                                                exportResult: ExportResult,
                                                exportException: ExportException
                                            ) {
                                                transformer = null
                                                running = false
                                                activeTemp = null
                                                temp.delete()
                                                progress = 0f
                                                status = "Export failed: ${exportException.message ?: exportException.errorCodeName}"
                                            }
                                        })
                                        .build()

                                    transformer = built
                                    built.start(edited, temp.absolutePath)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Encode and export") }

                            OutlinedButton(
                                enabled = running,
                                onClick = {
                                    transformer?.cancel()
                                    transformer = null
                                    activeTemp?.delete()
                                    activeTemp = null
                                    running = false
                                    progress = 0f
                                    status = "Cancelled."
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                        }

                        if (pendingEncodedFile != null && !running) {
                            OutlinedButton(
                                onClick = { saveRequestToken += 1 },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Save encoded file") }
                        }
                    }
                }

                Text(
                    "Export and playback are intentionally separate. Finishing an export no longer opens or auto-plays anything.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Screen.PLAYER -> PlayerPage(
                modifier = Modifier.padding(padding),
                uri = playerUri,
                lastExportUri = lastExportUri,
                onUseLastExport = { playerUri = lastExportUri },
                onPick = { playerPicker.launch(arrayOf("video/mp4", "video/*")) }
            )
        }
    }
}

private fun writeFileToUri(context: Context, source: File, destination: Uri): Long {
    require(source.exists() && source.length() > 0L) { "Encoded source is empty." }

    val descriptor = try {
        context.contentResolver.openFileDescriptor(destination, "rwt")
    } catch (_: Throwable) {
        context.contentResolver.openFileDescriptor(destination, "w")
    } ?: error("Could not open the destination file.")

    var copied = 0L
    descriptor.use { pfd ->
        FileOutputStream(pfd.fileDescriptor).use { output ->
            source.inputStream().buffered().use { input ->
                copied = input.copyTo(output, bufferSize = 1024 * 1024)
            }
            output.flush()
            runCatching { output.fd.sync() }
        }
    }

    require(copied == source.length()) {
        "Only $copied of ${source.length()} bytes were written."
    }
    return copied
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.2f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

@Composable
private fun PlayerPage(
    modifier: Modifier,
    uri: Uri?,
    lastExportUri: Uri?,
    onUseLastExport: () -> Unit,
    onPick: () -> Unit
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    LaunchedEffect(uri) {
        player?.release()
        player = uri?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(it))
                prepare()
                playWhenReady = false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    Column(modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPick, modifier = Modifier.weight(1f)) { Text("Open video") }
            OutlinedButton(
                onClick = onUseLastExport,
                enabled = lastExportUri != null,
                modifier = Modifier.weight(1f)
            ) { Text("Load last export") }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            color = Color.Black,
            shape = MaterialTheme.shapes.large
        ) {
            val active = player
            if (active == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No video loaded", color = Color.Gray)
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { player = active } },
                    update = { it.player = active }
                )
            }
        }
        Text(
            uri?.lastPathSegment ?: "The player stays empty until you explicitly open a file.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
