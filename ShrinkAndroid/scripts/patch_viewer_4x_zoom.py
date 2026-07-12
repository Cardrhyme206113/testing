from pathlib import Path

path = Path("ShrinkAndroid/app/src/main/java/com/cardrhyme/shrink/MainActivity.kt")
text = path.read_text(encoding="utf-8")

text = text.replace(
    "import androidx.compose.foundation.background\n",
    "import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n",
    1,
)
text = text.replace(
    "import androidx.compose.runtime.getValue\nimport androidx.compose.runtime.remember\n",
    "import androidx.compose.runtime.getValue\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\n",
    1,
)

player_anchor = '''    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
'''
player_replacement = player_anchor + '''    val zoomedCenter = remember { mutableStateOf(false) }
'''
if player_anchor not in text:
    raise SystemExit("Player state anchor not found")
text = text.replace(player_anchor, player_replacement, 1)

old_view = '''            AndroidView(
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
'''
new_view = '''            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        clipChildren = true
                        this.player = player
                    }
                },
                update = { view ->
                    view.player = player
                    val contentScale = if (zoomedCenter.value) 4f else 1f
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
'''
if old_view not in text:
    raise SystemExit("PlayerView anchor not found")
text = text.replace(old_view, new_view, 1)

badges_anchor = '''        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Badge(sourceSummary(state))
            Badge(state.lastCodec)
        }
'''
zoom_toggle = badges_anchor + '''
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
'''
if badges_anchor not in text:
    raise SystemExit("Viewer badge anchor not found")
text = text.replace(badges_anchor, zoom_toggle, 1)

path.write_text(text, encoding="utf-8")
print("Added 1x fit / 4x center viewer toggle")
