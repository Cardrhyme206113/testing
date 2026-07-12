package com.cardrhyme.shrink

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFEAF0FF),
                    onPrimary = Color(0xFF11151D),
                    surface = Color(0xFF11141B),
                    onSurface = Color(0xFFF5F7FB),
                    surfaceVariant = Color(0xFF171B24),
                    onSurfaceVariant = Color(0xFF98A2B5),
                    background = Color(0xFF08090D),
                    onBackground = Color(0xFFF5F7FB),
                    error = Color(0xFFFFB4AB),
                )
            ) {
                ShrinkApp()
            }
        }
    }
}

@Composable
private fun ShrinkApp(viewModel: ShrinkViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModel.selectSource(uri)
    }

    LaunchedEffect(state.errorMessage, state.successMessage) {
        val message = state.errorMessage ?: state.successMessage ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { insets ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF111229), Color(0xFF08090D), Color(0xFF08090D)),
                    )
                )
                .padding(insets)
                .padding(8.dp),
        ) {
            val landscapeCompact = maxWidth > maxHeight && maxHeight < 540.dp
            val compact = maxHeight < 760.dp

            if (landscapeCompact) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PreviewCard(
                        state = state,
                        modifier = Modifier.weight(1.2f).fillMaxHeight(),
                    )
                    ControlPanel(
                        state = state,
                        compact = true,
                        modifier = Modifier
                            .widthIn(min = 330.dp, max = 410.dp)
                            .fillMaxHeight(),
                        onChoose = { picker.launch(arrayOf("video/*")) },
                        onPreview = viewModel::createPreview,
                        onCompress = viewModel::compressFullVideo,
                        onCancel = viewModel::cancel,
                        onPosition = viewModel::setPosition,
                        onResolution = viewModel::setResolution,
                        onBitrate = viewModel::setBitrate,
                        onFps = viewModel::setFps,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
                ) {
                    PreviewCard(
                        state = state,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    ControlPanel(
                        state = state,
                        compact = compact,
                        modifier = Modifier.fillMaxWidth(),
                        onChoose = { picker.launch(arrayOf("video/*")) },
                        onPreview = viewModel::createPreview,
                        onCompress = viewModel::compressFullVideo,
                        onCancel = viewModel::cancel,
                        onPosition = viewModel::setPosition,
                        onResolution = viewModel::setResolution,
                        onBitrate = viewModel::setBitrate,
                        onFps = viewModel::setFps,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(
    state: ShrinkViewModel.UiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(state.playbackUri) {
        val uri = state.playbackUri
        player.stop()
        player.clearMediaItems()
        if (uri != null) {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = uri != state.sourceUri
        }
    }

    LaunchedEffect(state.positionMs, state.playbackUri, state.sourceUri) {
        if (state.playbackUri != null && state.playbackUri == state.sourceUri) {
            player.seekTo(state.positionMs)
        }
    }

    Box(
        modifier = modifier
            .sizeIn(minHeight = 120.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp)),
    ) {
        if (state.playbackUri != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }
                },
                update = { it.player = player },
            )
        } else {
            Text(
                text = "Choose a video",
                color = Color(0xFFAAB2C0),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Badge(sourceSummary(state))
            Badge(state.lastCodec)
        }
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xB312151C))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ControlPanel(
    state: ShrinkViewModel.UiState,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onChoose: () -> Unit,
    onPreview: () -> Unit,
    onCompress: () -> Unit,
    onCancel: () -> Unit,
    onPosition: (Long) -> Unit,
    onResolution: (Int) -> Unit,
    onBitrate: (Float) -> Unit,
    onFps: (Int) -> Unit,
) {
    val gap = if (compact) 3.dp else 6.dp
    val rowHeight = if (compact) 29.dp else 35.dp
    val positionMaxSeconds = ((state.durationMs - 1_000L).coerceAtLeast(0L) / 1_000f)
    val positionSeconds = (state.positionMs / 1_000f).coerceIn(0f, positionMaxSeconds.coerceAtLeast(0f))

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 18.dp else 22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF171A23), Color(0xFF10131A))))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(if (compact) 18.dp else 22.dp),
            )
            .padding(if (compact) 7.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        SliderRow(
            label = "Position",
            value = formatPosition(state.positionMs, state.durationMs),
            sliderValue = positionSeconds,
            range = 0f..positionMaxSeconds.coerceAtLeast(0.01f),
            steps = 0,
            enabled = state.sourceUri != null && !state.isWorking,
            rowHeight = rowHeight,
            onValueChange = { onPosition((it * 1_000f).roundToInt().toLong()) },
        )
        SliderRow(
            label = "Resolution",
            value = ShrinkViewModel.RESOLUTION_LABELS[state.resolutionIndex],
            sliderValue = state.resolutionIndex.toFloat(),
            range = 0f..4f,
            steps = 3,
            enabled = !state.isWorking,
            rowHeight = rowHeight,
            onValueChange = { onResolution(it.roundToInt()) },
        )
        SliderRow(
            label = "Bitrate",
            value = formatBitrate(state.bitrateMbps),
            sliderValue = state.bitrateMbps,
            range = 2.5f..25f,
            steps = 8,
            enabled = !state.isWorking,
            rowHeight = rowHeight,
            onValueChange = onBitrate,
        )
        SliderRow(
            label = "Frame rate",
            value = "${ShrinkViewModel.FPS_VALUES[state.fpsIndex]} fps",
            sliderValue = state.fpsIndex.toFloat(),
            range = 0f..2f,
            steps = 1,
            enabled = !state.isWorking,
            rowHeight = rowHeight,
            onValueChange = { onFps(it.roundToInt()) },
        )

        EstimateCard(state, compact)

        if (state.isWorking) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.weight(1f),
                        color = Color(0xFFAFC4FF),
                        trackColor = Color.White.copy(alpha = 0.10f),
                    )
                    Text(
                        text = "${state.progress}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDCE6FF),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.status,
                        color = Color(0xFF98A2B5),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.height(if (compact) 34.dp else 38.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("Cancel", fontSize = 11.sp)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactButton(
                    text = "Choose",
                    primary = false,
                    enabled = true,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onClick = onChoose,
                )
                CompactButton(
                    text = "Preview 1s",
                    primary = false,
                    enabled = state.sourceUri != null,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onClick = onPreview,
                )
                CompactButton(
                    text = "Compress",
                    primary = true,
                    enabled = state.sourceUri != null,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onClick = onCompress,
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    rowHeight: Dp,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = label.uppercase(),
            modifier = Modifier.width(72.dp),
            color = Color(0xFF8E97A8),
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
        )
        Slider(
            value = sliderValue.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFDCE7FF),
                activeTrackColor = Color(0xFF8AA8FF),
                inactiveTrackColor = Color.White.copy(alpha = 0.13f),
                disabledThumbColor = Color(0xFF697181),
                disabledActiveTrackColor = Color(0xFF596273),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.08f),
            ),
        )
        Text(
            text = value,
            modifier = Modifier.width(78.dp),
            color = Color(0xFFDCE7FF),
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun EstimateCard(state: ShrinkViewModel.UiState, compact: Boolean) {
    val estimate = estimatedSizeMb(state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFF1F5FF), Color(0xFFD9E8FF))))
            .padding(horizontal = 10.dp, vertical = if (compact) 7.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ESTIMATED OUTPUT",
                color = Color(0xFF526071),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.6.sp,
            )
            Text(
                text = estimateDetail(state, estimate),
                color = Color(0xFF596577),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (estimate == null) "— MB" else "${formatEstimate(estimate)} MB",
            color = Color(0xFF11151D),
            fontSize = if (compact) 23.sp else 27.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.8).sp,
        )
    }
}

@Composable
private fun CompactButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (compact) 36.dp else 42.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors(
                containerColor = Color(0xFFF1F5FF),
                contentColor = Color(0xFF11151D),
                disabledContainerColor = Color(0xFF343945),
                disabledContentColor = Color(0xFF858C99),
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = Color(0xFF202532),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF181C24),
                disabledContentColor = Color(0xFF686F7D),
            )
        },
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

private fun sourceSummary(state: ShrinkViewModel.UiState): String {
    if (state.sourceUri == null) return "No source"
    val sizeMb = state.sourceSizeBytes / 1_000_000.0
    return "${state.sourceWidth}×${state.sourceHeight} • ${formatDuration(state.durationMs)} • ${"%.1f".format(sizeMb)} MB"
}

private fun formatPosition(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return "00:00"
    return "${formatDuration(positionMs)} / ${formatDuration(durationMs)}"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatBitrate(value: Float): String {
    return if (value % 1f == 0f) "${value.toInt()} Mbps" else "${"%.1f".format(value)} Mbps"
}

private fun estimatedSizeMb(state: ShrinkViewModel.UiState): Double? {
    if (state.durationMs <= 0L) return null
    val durationSeconds = state.durationMs / 1_000.0
    val totalMbps = state.bitrateMbps + 0.192
    return totalMbps * durationSeconds / 8.0 * 1.01
}

private fun formatEstimate(value: Double): String {
    return if (value >= 100.0) value.roundToInt().toString() else "%.1f".format(value)
}

private fun estimateDetail(state: ShrinkViewModel.UiState, estimateMb: Double?): String {
    if (estimateMb == null) return "Load a video to calculate"
    val targetHeight = ShrinkViewModel.RESOLUTION_HEIGHTS[state.resolutionIndex]
    val resolution = if (state.sourceHeight > 0 && targetHeight > state.sourceHeight) {
        "${state.sourceHeight}p source cap"
    } else {
        ShrinkViewModel.RESOLUTION_LABELS[state.resolutionIndex]
    }
    val fps = ShrinkViewModel.FPS_VALUES[state.fpsIndex]
    if (state.sourceSizeBytes <= 0L) return "$resolution • $fps fps • includes 192 kbps audio"
    val sourceMb = state.sourceSizeBytes / 1_000_000.0
    val reduction = (1.0 - estimateMb / sourceMb) * 100.0
    val comparison = if (reduction >= 0) {
        "${reduction.roundToInt()}% smaller"
    } else {
        "${(-reduction).roundToInt()}% larger"
    }
    return "$resolution • $fps fps • $comparison"
}
