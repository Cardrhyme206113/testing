from pathlib import Path

ui_path = Path("ShrinkAndroid/app/src/main/java/com/cardrhyme/shrink/MainActivity.kt")
vm_path = Path("ShrinkAndroid/app/src/main/java/com/cardrhyme/shrink/ShrinkViewModel.kt")

text = ui_path.read_text(encoding="utf-8")

if "import kotlinx.coroutines.delay\n" not in text:
    text = text.replace(
        "import androidx.media3.ui.PlayerView\n",
        "import androidx.media3.ui.PlayerView\nimport kotlinx.coroutines.delay\n",
        1,
    )
if "import kotlin.math.abs\n" not in text:
    text = text.replace(
        "import kotlin.math.roundToInt\n",
        "import kotlin.math.abs\nimport kotlin.math.roundToInt\n",
        1,
    )

start = text.index("@Composable\nprivate fun PreviewCard(")
end = text.index("@Composable\nprivate fun Badge", start)

replacement = r'''@Composable
private fun PreviewCard(
    state: ShrinkViewModel.UiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sourcePlayer = remember {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
    }
    val resultPlayer = remember {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
    }
    val zoomedCenter = remember { mutableStateOf(false) }
    val comparisonActive = state.sourceUri != null &&
        state.playbackUri != null &&
        state.playbackUri != state.sourceUri

    DisposableEffect(sourcePlayer, resultPlayer) {
        onDispose {
            sourcePlayer.release()
            resultPlayer.release()
        }
    }

    LaunchedEffect(state.sourceUri, state.playbackUri, state.positionMs) {
        sourcePlayer.stop()
        sourcePlayer.clearMediaItems()
        resultPlayer.stop()
        resultPlayer.clearMediaItems()

        if (comparisonActive) {
            sourcePlayer.setMediaItem(MediaItem.fromUri(state.sourceUri!!))
            sourcePlayer.prepare()
            sourcePlayer.seekTo(state.positionMs)

            resultPlayer.setMediaItem(MediaItem.fromUri(state.playbackUri!!))
            resultPlayer.prepare()
            resultPlayer.seekTo(0L)

            sourcePlayer.playWhenReady = true
            resultPlayer.playWhenReady = true
        } else {
            val uri = state.playbackUri
            if (uri != null) {
                sourcePlayer.setMediaItem(MediaItem.fromUri(uri))
                sourcePlayer.prepare()
                if (uri == state.sourceUri) sourcePlayer.seekTo(state.positionMs)
                sourcePlayer.playWhenReady = false
            }
        }
    }

    LaunchedEffect(comparisonActive, state.playbackUri, state.positionMs) {
        while (comparisonActive) {
            delay(250L)
            if (resultPlayer.playbackState == Player.STATE_ENDED) {
                sourcePlayer.pause()
            } else if (resultPlayer.isPlaying) {
                val expectedSourcePosition = state.positionMs + resultPlayer.currentPosition
                if (abs(sourcePlayer.currentPosition - expectedSourcePosition) > 100L) {
                    sourcePlayer.seekTo(expectedSourcePosition)
                }
                if (!sourcePlayer.isPlaying) sourcePlayer.play()
            } else if (sourcePlayer.isPlaying) {
                sourcePlayer.pause()
            }
        }
    }

    Box(
        modifier = modifier
            .sizeIn(minHeight = 120.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp)),
    ) {
        when {
            comparisonActive -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            val shouldPause = sourcePlayer.isPlaying || resultPlayer.isPlaying
                            if (shouldPause) {
                                sourcePlayer.pause()
                                resultPlayer.pause()
                            } else {
                                if (resultPlayer.playbackState == Player.STATE_ENDED) {
                                    sourcePlayer.seekTo(state.positionMs)
                                    resultPlayer.seekTo(0L)
                                }
                                sourcePlayer.play()
                                resultPlayer.play()
                            }
                        },
                ) {
                    ComparisonPane(
                        player = sourcePlayer,
                        label = "BEFORE",
                        zoomedCenter = zoomedCenter.value,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(Color.White.copy(alpha = 0.72f)),
                    )
                    ComparisonPane(
                        player = resultPlayer,
                        label = "AFTER • ${state.lastCodec}",
                        zoomedCenter = zoomedCenter.value,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }

            state.playbackUri != null -> {
                ComparisonPane(
                    player = sourcePlayer,
                    label = "",
                    zoomedCenter = zoomedCenter.value,
                    showController = true,
                    modifier = Modifier.fillMaxSize(),
                )
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

            else -> {
                Text(
                    text = "Choose a video",
                    color = Color(0xFFAAB2C0),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        if (state.playbackUri != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xD812151C))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                    .clickable { zoomedCenter.value = !zoomedCenter.value }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Text(
                    text = if (zoomedCenter.value) "4× CENTER" else "1× FIT",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.4.sp,
                )
            }
        }
    }
}

@Composable
private fun ComparisonPane(
    player: ExoPlayer,
    label: String,
    zoomedCenter: Boolean,
    modifier: Modifier = Modifier,
    showController: Boolean = false,
) {
    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = showController
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    clipChildren = true
                    this.player = player
                }
            },
            update = { view ->
                view.player = player
                view.useController = showController
                val contentScale = if (zoomedCenter) 4f else 1f
                view.post {
                    view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)
                        ?.apply {
                            pivotX = width / 2f
                            pivotY = height / 2f
                            scaleX = contentScale
                            scaleY = contentScale
                        }
                }
            },
        )
        if (label.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xC812151C))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

'''

text = text[:start] + replacement + text[end:]
ui_path.write_text(text, encoding="utf-8")

vm = vm_path.read_text(encoding="utf-8")
old = '''                _state.value = _state.value.copy(
                    playbackUri = published,
                    lastCodec = result.codecLabel,
                    successMessage = "Saved to Movies/Shrink using ${result.codecLabel}",
                )
'''
new = '''                _state.value = _state.value.copy(
                    playbackUri = published,
                    positionMs = 0L,
                    lastCodec = result.codecLabel,
                    successMessage = "Saved to Movies/Shrink using ${result.codecLabel}",
                )
'''
if old not in vm:
    raise SystemExit("Full-export state anchor not found")
vm = vm.replace(old, new, 1)
vm_path.write_text(vm, encoding="utf-8")

print("Added synchronized left/right before-after comparison")
