package com.cardrhyme.sharplayer.ui

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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Screen { EXPORT, PLAYER }

@OptIn(UnstableApi::class)
@Composable
fun SharpLayerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.EXPORT) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var output by remember { mutableStateOf<Uri?>(null) }
    var playerUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("Choose a source video.") }
    var progress by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        source = uri
        if (uri != null) status = "Source selected: ${uri.lastPathSegment ?: "video"}"
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        output = uri
        if (uri != null) status = "Output selected. Ready to export."
    }
    val playerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        playerUri = uri
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("SharpLayer", style = MaterialTheme.typography.headlineMedium)
                    Text("Hardware video export and playback", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Source", style = MaterialTheme.typography.titleMedium)
                        Text(source?.lastPathSegment ?: "No source selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { sourcePicker.launch(arrayOf("video/*")) }, enabled = !running) { Text("Choose video") }
                    }
                }
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Output", style = MaterialTheme.typography.titleMedium)
                        Text(output?.lastPathSegment ?: "No output selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { outputPicker.launch("sharplayer-output.mp4") }, enabled = !running) { Text("Choose output") }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = source != null && output != null && !running,
                                onClick = {
                                    val input = source ?: return@Button
                                    val destination = output ?: return@Button
                                    running = true
                                    progress = 0.05f
                                    status = "Starting Android hardware transcoder…"
                                    val temp = File(context.cacheDir, "sharplayer-${System.currentTimeMillis()}.mp4")
                                    val built = Transformer.Builder(context)
                                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                                        .addListener(object : Transformer.Listener {
                                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                                scope.launch {
                                                    try {
                                                        progress = 0.9f
                                                        status = "Writing final MP4…"
                                                        withContext(Dispatchers.IO) {
                                                            context.contentResolver.openOutputStream(destination, "w")!!.use { out ->
                                                                temp.inputStream().use { inputStream -> inputStream.copyTo(out) }
                                                            }
                                                            temp.delete()
                                                        }
                                                        progress = 1f
                                                        status = "Export complete."
                                                        playerUri = destination
                                                    } catch (t: Throwable) {
                                                        status = "Write failed: ${t.message}"
                                                    } finally {
                                                        running = false
                                                    }
                                                }
                                            }

                                            override fun onError(
                                                composition: Composition,
                                                exportResult: ExportResult,
                                                exportException: ExportException
                                            ) {
                                                status = "Export failed: ${exportException.message}"
                                                running = false
                                                temp.delete()
                                            }
                                        })
                                        .build()
                                    transformer = built
                                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(input)).build()
                                    built.start(edited, temp.absolutePath)
                                    progress = 0.2f
                                    status = "Encoding H.264/AAC with MediaCodec…"
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Export") }
                            OutlinedButton(
                                enabled = running,
                                onClick = {
                                    transformer?.cancel()
                                    running = false
                                    status = "Cancelled."
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                        }
                    }
                }
                Text(
                    "This native baseline proves hardware transcoding, file export, and playback without FFmpeg. The dual-layer residual format is the next stage.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Screen.PLAYER -> PlayerPage(
                modifier = Modifier.padding(padding),
                uri = playerUri,
                onPick = { playerPicker.launch(arrayOf("video/mp4", "video/*")) }
            )
        }
    }
}

@Composable
private fun PlayerPage(modifier: Modifier, uri: Uri?, onPick: () -> Unit) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    LaunchedEffect(uri) {
        player?.release()
        player = uri?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(it))
                prepare()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    Column(modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Open video") }
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            color = Color.Black,
            shape = MaterialTheme.shapes.large
        ) {
            val active = player
            if (active == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No video loaded", color = Color.Gray) }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { player = active } },
                    update = { it.player = active }
                )
            }
        }
        Text(uri?.lastPathSegment ?: "Choose a normal MP4 or a future SharpLayer file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
