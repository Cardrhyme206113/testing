package com.cardrhyme.sharplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cardrhyme.sharplayer.codec.StructureCodec
import com.cardrhyme.sharplayer.export.OptionDExportEngine
import com.cardrhyme.sharplayer.player.StructureOverlayView
import kotlinx.coroutines.Dispatchers
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
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val engine = remember { OptionDExportEngine(context.applicationContext) }

    var screen by remember { mutableStateOf(Screen.EXPORT) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var playerSource by remember { mutableStateOf<Uri?>(null) }
    var preparedMedia by remember { mutableStateOf<StructureCodec.PreparedMedia?>(null) }
    var lastExportUri by remember { mutableStateOf<Uri?>(null) }

    var targetHeight by remember { mutableIntStateOf(1080) }
    var totalBitrateKbps by remember { mutableIntStateOf(300) }
    var status by remember { mutableStateOf("Choose a source video.") }
    var playerStatus by remember { mutableStateOf("Open a normal MP4 or a SharpLayer Option D file.") }
    var progress by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("Idle") }
    var pendingResult by remember { mutableStateOf<OptionDExportEngine.Result?>(null) }
    var saveRequestToken by remember { mutableIntStateOf(0) }

    val isFullscreenPlayer =
        screen == Screen.PLAYER && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    DisposableEffect(activity, isFullscreenPlayer) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            WindowCompat.setDecorFitsSystemWindows(window, !isFullscreenPlayer)
            if (isFullscreenPlayer) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (isFullscreenPlayer && window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        source = uri
        if (uri != null) {
            status = "Source selected: ${uri.lastPathSegment ?: "video"}"
            stage = "Ready"
            progress = 0f
        }
    }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destination ->
        val result = pendingResult
        if (destination == null || result == null) {
            status = if (result != null) "Encoded file remains available. Tap Save again." else "Save cancelled."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            running = true
            stage = "Saving"
            status = "Writing ${formatBytes(result.file.length())}…"
            try {
                val written = withContext(Dispatchers.IO) {
                    writeFileToUri(context, result.file, destination)
                }
                require(written == result.file.length()) { "Only $written bytes were written." }
                lastExportUri = destination
                status = "Saved ${formatBytes(written)} · measured ${result.actualTotalKbps} kbps total."
                stage = "Done"
                progress = 1f
                result.file.delete()
                pendingResult = null
            } catch (t: Throwable) {
                status = "Save failed: ${t.message}. The encoded cache file is still available."
            } finally {
                running = false
            }
        }
    }

    val playerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playerSource = uri
    }

    LaunchedEffect(saveRequestToken) {
        val result = pendingResult
        if (saveRequestToken > 0 && result != null) {
            savePicker.launch(
                "SharpLayer-OptionD-${targetHeight}p-${result.actualTotalKbps}kbps.mp4"
            )
        }
    }

    LaunchedEffect(playerSource) {
        val uri = playerSource ?: return@LaunchedEffect
        playerStatus = "Inspecting and preparing video…"
        val old = preparedMedia
        preparedMedia = null
        old?.close()
        try {
            val prepared = StructureCodec.prepareForPlayback(context, uri)
            preparedMedia = prepared
            playerStatus = if (prepared.sequence != null) {
                val metadata = prepared.metadata
                "Option D structure layer · ${prepared.sequence.fps} Hz · " +
                    "${metadata?.optInt("actualTotalKbps", 0)?.takeIf { it > 0 }?.let { "$it kbps" } ?: "layered file"}"
            } else {
                "Normal MP4 · no SharpLayer structure stream"
            }
        } catch (t: Throwable) {
            playerStatus = "Could not open video: ${t.message}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.cancel()
            pendingResult?.file?.delete()
            preparedMedia?.close()
        }
    }

    Scaffold(
        topBar = {
            if (!isFullscreenPlayer) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("SharpLayer Option D", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Depth + segmentation + 10 Hz structural deltas",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!isFullscreenPlayer) {
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
        }
    ) { padding ->
        when (screen) {
            Screen.EXPORT -> ExportPage(
                modifier = Modifier.padding(padding),
                source = source,
                targetHeight = targetHeight,
                totalBitrateKbps = totalBitrateKbps,
                status = status,
                stage = stage,
                progress = progress,
                running = running,
                pendingResult = pendingResult,
                onPickSource = { sourcePicker.launch(arrayOf("video/*")) },
                onHeightChanged = { targetHeight = it },
                onBitrateChanged = { totalBitrateKbps = it },
                onSaveAgain = { saveRequestToken += 1 },
                onCancel = {
                    engine.cancel()
                    running = false
                    progress = 0f
                    stage = "Cancelled"
                    status = "Export cancelled."
                },
                onStart = {
                    val input = source ?: return@ExportPage
                    pendingResult?.file?.delete()
                    pendingResult = null
                    running = true
                    progress = 0f
                    stage = "Starting"
                    status = "Preparing Option D export…"
                    scope.launch {
                        try {
                            val result = engine.export(
                                source = input,
                                settings = OptionDExportEngine.Settings(
                                    totalBitrateKbps = totalBitrateKbps,
                                    outputHeight = targetHeight
                                ),
                                onUpdate = { update ->
                                    scope.launch(Dispatchers.Main) {
                                        progress = update.progress.coerceIn(0f, 1f)
                                        stage = update.stage
                                        status = update.detail
                                    }
                                }
                            )
                            pendingResult = result
                            progress = 1f
                            stage = "Encoded"
                            status =
                                "Measured ${result.actualTotalKbps} kbps total · " +
                                    "video ${result.requestedVideoKbps} · structure ${result.structureKbps} · audio ${result.audioKbps}."
                            saveRequestToken += 1
                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) {
                                status = "Export cancelled."
                                stage = "Cancelled"
                            } else {
                                status = "Export failed: ${t.message ?: t::class.java.simpleName}"
                                stage = "Failed"
                            }
                            progress = 0f
                        } finally {
                            running = false
                        }
                    }
                }
            )

            Screen.PLAYER -> PlayerPage(
                modifier = if (isFullscreenPlayer) Modifier.fillMaxSize() else Modifier.padding(padding),
                prepared = preparedMedia,
                status = playerStatus,
                lastExportUri = lastExportUri,
                fullscreen = isFullscreenPlayer,
                onUseLastExport = { playerSource = lastExportUri },
                onPick = { playerPicker.launch(arrayOf("video/mp4", "video/*")) },
                onEnterFullscreen = {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                },
                onExitFullscreen = {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            )
        }
    }
}

@Composable
private fun ExportPage(
    modifier: Modifier,
    source: Uri?,
    targetHeight: Int,
    totalBitrateKbps: Int,
    status: String,
    stage: String,
    progress: Float,
    running: Boolean,
    pendingResult: OptionDExportEngine.Result?,
    onPickSource: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    onBitrateChanged: (Int) -> Unit,
    onSaveAgain: () -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Source", style = MaterialTheme.typography.titleMedium)
                Text(source?.lastPathSegment ?: "No source selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onPickSource, enabled = !running) { Text("Choose video") }
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Strict total-file target", style = MaterialTheme.typography.titleMedium)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Output height")
                    Text("${targetHeight}p", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = targetHeight.toFloat(),
                    onValueChange = {
                        onHeightChanged(((it / 120f).roundToInt() * 120).coerceIn(360, 1080))
                    },
                    valueRange = 360f..1080f,
                    steps = 5,
                    enabled = !running
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total output bitrate")
                    Text("$totalBitrateKbps kbps", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = totalBitrateKbps.toFloat(),
                    onValueChange = {
                        onBitrateChanged(((it / 10f).roundToInt() * 10).coerceIn(120, 2_000))
                    },
                    valueRange = 120f..2_000f,
                    steps = 187,
                    enabled = !running
                )

                Text(
                    "This is the measured target for the complete file, not merely a request to the encoder. " +
                        "H.264 uses CBR; oversized results are automatically retried at a lower video rate.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Option D structure layer", style = MaterialTheme.typography.titleMedium)
                Text("• MiDaS monocular depth", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• DeepLab-v3 semantic regions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• Faithful gradient lineart + depth/normal edges", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• 10 updates/sec, 1-second keyframes, relative changes between them", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stage)
                    Text("${(progress * 100).roundToInt()}%")
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = source != null && !running,
                        onClick = onStart,
                        modifier = Modifier.weight(1f)
                    ) { Text("Encode Option D") }
                    OutlinedButton(
                        enabled = running,
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel") }
                }

                if (pendingResult != null && !running) {
                    OutlinedButton(onClick = onSaveAgain, modifier = Modifier.fillMaxWidth()) {
                        Text("Save encoded file again")
                    }
                }
            }
        }

        Text(
            "The MP4 remains playable in ordinary players as the H.264 fallback. SharpLayer reads the appended structure stream and overlays it during playback.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlayerPage(
    modifier: Modifier,
    prepared: StructureCodec.PreparedMedia?,
    status: String,
    lastExportUri: Uri?,
    fullscreen: Boolean,
    onUseLastExport: () -> Unit,
    onPick: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var structureEnabled by remember { mutableStateOf(true) }
    var opacity by remember { mutableFloatStateOf(0.72f) }

    LaunchedEffect(prepared?.baseFile?.absolutePath) {
        player?.release()
        player = prepared?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(it.baseFile)))
                prepare()
                playWhenReady = false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    if (fullscreen) {
        Box(modifier.background(Color.Black)) {
            PlayerSurface(
                player = player,
                sequence = if (structureEnabled) prepared?.sequence else null,
                opacity = opacity,
                modifier = Modifier.fillMaxSize(),
                rounded = false
            )
            FilledTonalButton(
                onClick = onExitFullscreen,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("Exit fullscreen") }
        }
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPick, modifier = Modifier.weight(1f)) { Text("Open video") }
            OutlinedButton(
                onClick = onUseLastExport,
                enabled = lastExportUri != null,
                modifier = Modifier.weight(1f)
            ) { Text("Load last export") }
        }

        PlayerSurface(
            player = player,
            sequence = if (structureEnabled) prepared?.sequence else null,
            opacity = opacity,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            rounded = true
        )

        if (prepared?.sequence != null) {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = structureEnabled, onCheckedChange = { structureEnabled = it })
                        Spacer(Modifier.width(10.dp))
                        Text("Option D reconstruction overlay")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Line strength")
                        Text("${(opacity * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.15f..1f)
                }
            }
        }

        Button(
            onClick = onEnterFullscreen,
            enabled = player != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Fullscreen landscape") }

        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Rotating sideways on the Player tab enters immersive fullscreen automatically.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PlayerSurface(
    player: ExoPlayer?,
    sequence: StructureCodec.Sequence?,
    opacity: Float,
    modifier: Modifier,
    rounded: Boolean
) {
    Surface(
        modifier = modifier,
        color = Color.Black,
        shape = if (rounded) RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp)
    ) {
        if (player == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No video loaded", color = Color.Gray)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                        }
                    },
                    update = { it.player = player }
                )
                if (sequence != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> StructureOverlayView(ctx) },
                        update = { overlay ->
                            overlay.bind(player, sequence)
                            overlay.setLineOpacity(opacity)
                        }
                    )
                }
            }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
