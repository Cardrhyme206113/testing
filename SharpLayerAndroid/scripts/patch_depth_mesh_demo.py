from pathlib import Path
import re

root = Path("SharpLayerAndroid/app/src/main/java/com/cardrhyme/sharplayer")
analyzer_path = root / "codec/FastStructureAnalyzer.kt"
engine_path = root / "export/OptionDExportEngine.kt"
ui_path = root / "ui/SharpLayerApp.kt"
mesh_path = root / "player/DepthMeshPlayerView.kt"

# 24 fps structural timeline, depth refresh at 6 fps, segmentation at 12 fps.
analyzer = analyzer_path.read_text(encoding="utf-8")
analyzer = analyzer.replace("const val UPDATE_FPS = 5", "const val UPDATE_FPS = 24")
analyzer = analyzer.replace("const val DEPTH_REFRESH_FPS = 1f", "const val DEPTH_REFRESH_FPS = 6f")
analyzer = analyzer.replace("const val SEGMENTATION_REFRESH_FPS = 0.5f", "const val SEGMENTATION_REFRESH_FPS = 12f")
analyzer = analyzer.replace("private const val FRAME_INTERVAL_MS = 200L", "private const val FRAME_INTERVAL_MS = 42L")
analyzer = analyzer.replace("private const val KEYFRAME_INTERVAL = 10 // 2 seconds at 5 Hz", "private const val KEYFRAME_INTERVAL = 24 // one second at 24 Hz")
analyzer = analyzer.replace("private const val DEPTH_INTERVAL = 5", "private const val DEPTH_INTERVAL = 4")
analyzer = analyzer.replace("private const val SEGMENTATION_INTERVAL = 10", "private const val SEGMENTATION_INTERVAL = 2")

old_sig = '''        source: Uri,
        structureBudgetKbps: Int,
        onProgress: (Float, String) -> Unit,
'''
new_sig = '''        source: Uri,
        structureBudgetKbps: Int,
        clipStartMs: Long,
        clipDurationMs: Long,
        onProgress: (Float, String) -> Unit,
'''
if old_sig not in analyzer:
    raise SystemExit("analyzer signature anchor not found")
analyzer = analyzer.replace(old_sig, new_sig, 1)

old_duration = '''            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L)
                ?: error("Could not read video duration")
'''
new_duration = '''            val sourceDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L)
                ?: error("Could not read video duration")
            val safeClipStartMs = clipStartMs.coerceIn(0L, (sourceDurationMs - 1L).coerceAtLeast(0L))
            val durationMs = clipDurationMs.coerceAtLeast(1L)
                .coerceAtMost((sourceDurationMs - safeClipStartMs).coerceAtLeast(1L))
'''
if old_duration not in analyzer:
    raise SystemExit("duration anchor not found")
analyzer = analyzer.replace(old_duration, new_duration, 1)

analyzer = analyzer.replace(
    "val timeMs = min(durationMs - 1L, frameIndex * FRAME_INTERVAL_MS)",
    "val relativeTimeMs = min(durationMs - 1L, frameIndex * FRAME_INTERVAL_MS)\n                val timeMs = safeClipStartMs + relativeTimeMs",
    1,
)
analyzer = analyzer.replace(
    "frames += StructureCodec.Frame(timeMs.toInt(), keyframe, payload)",
    "frames += StructureCodec.Frame(relativeTimeMs.toInt(), keyframe, payload)",
    1,
)

# Replace edge-category state with temporally stabilized quantized depth walls.
state_pattern = re.compile(
    r'''                    val wasActive = previousState\[i\]\.toInt\(\) != 0\n                    val activeNow = if \(wasActive\) \{.*?                    nextState\[i\] = state\.toByte\(\)\n                    if \(state != 0\) active\+\+\n''',
    re.DOTALL,
)
state_replacement = '''                    val previousLevel = previousState[i].toInt() and 3
                    val rawLevel = (depth[i].coerceIn(0f, 1f) * 3f).roundToInt().coerceIn(0, 3)
                    // Semantic/depth discontinuities are treated as wall breaks rather than blurred edges.
                    val boundary = semantic[i] > 0.5f || depthEdge[i] > 0.72f
                    val state = if (boundary) rawLevel else {
                        val blended = previousLevel * 0.35f + rawLevel * 0.65f
                        blended.roundToInt().coerceIn(0, 3)
                    }
                    nextState[i] = state.toByte()
                    active++
'''
analyzer, count = state_pattern.subn(state_replacement, analyzer, count=1)
if count != 1:
    raise SystemExit(f"depth-state replacement failed: {count}")
analyzer_path.write_text(analyzer, encoding="utf-8")

# Select one random-ish five-second sample around the middle and clip both analysis and encode to it.
engine = engine_path.read_text(encoding="utf-8")
engine = engine.replace("import android.media.MediaCodecInfo\n", "import android.media.MediaCodecInfo\nimport android.media.MediaMetadataRetriever\n")
engine = engine.replace("import kotlin.math.roundToInt\n", "import kotlin.math.roundToInt\nimport kotlin.random.Random\n")
anchor = "        val session = System.currentTimeMillis()\n        val workDir = File(context.cacheDir, \"sharp-option-d-$session\").apply { mkdirs() }\n"
insert = '''        val session = System.currentTimeMillis()
        val sourceDurationMs = withContext(Dispatchers.IO) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, source)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
            }
        }
        val demoDurationMs = minOf(5_000L, sourceDurationMs)
        val maxStart = (sourceDurationMs - demoDurationMs).coerceAtLeast(0L)
        val middleStart = maxStart / 2L
        val jitter = minOf(2_000L, maxStart / 4L)
        val demoStartMs = if (jitter > 0L) {
            (middleStart + Random(session).nextLong(-jitter, jitter + 1L)).coerceIn(0L, maxStart)
        } else middleStart
        val demoEndMs = demoStartMs + demoDurationMs
        val workDir = File(context.cacheDir, "sharp-option-d-$session").apply { mkdirs() }
'''
if anchor not in engine:
    raise SystemExit("engine session anchor not found")
engine = engine.replace(anchor, insert, 1)

engine = engine.replace(
    '''                structureBudgetKbps = structureBudget,
                onProgress = { p, detail ->''',
    '''                structureBudgetKbps = structureBudget,
                clipStartMs = demoStartMs,
                clipDurationMs = demoDurationMs,
                onProgress = { p, detail ->''',
    1,
)
engine = engine.replace(
    '"Encoder passed (${encoderPlan.frameRate} fps ${encoderPlan.label}); starting models",',
    '"Processing a ${demoDurationMs / 1000.0}s sample near the middle · 6 fps depth · 12 fps segmentation · 24 fps propagation",',
    1,
)
engine = engine.replace("clipEndMs = null,", "clipStartMs = demoStartMs,\n                    clipEndMs = demoEndMs,", 1)
engine = engine.replace("clipEndMs = 700L,", "clipStartMs = null,\n                    clipEndMs = 700L,", 1)
engine = engine.replace(
    "        clipEndMs: Long?,\n        reportProgress: Boolean,",
    "        clipStartMs: Long?,\n        clipEndMs: Long?,\n        reportProgress: Boolean,",
    1,
)
old_clip = '''            if (clipEndMs != null) {
                mediaItemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(clipEndMs)
                        .build()
                )
            }
'''
new_clip = '''            if (clipStartMs != null || clipEndMs != null) {
                val clipping = MediaItem.ClippingConfiguration.Builder()
                clipStartMs?.let { clipping.setStartPositionMs(it) }
                clipEndMs?.let { clipping.setEndPositionMs(it) }
                mediaItemBuilder.setClippingConfiguration(clipping.build())
            }
'''
if old_clip not in engine:
    raise SystemExit("clipping block anchor not found")
engine = engine.replace(old_clip, new_clip, 1)
engine = engine.replace('.put("outputFrameRate", encoderPlan.frameRate)', '.put("outputFrameRate", 24)\n                    .put("sampleStartMs", demoStartMs)\n                    .put("sampleDurationMs", demoDurationMs)\n                    .put("reconstruction", "textured depth mesh")', 1)
engine_path.write_text(engine, encoding="utf-8")

mesh_code = r'''package com.cardrhyme.sharplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import com.cardrhyme.sharplayer.codec.StructureCodec
import kotlin.math.max

/**
 * 2.5D reconstruction prototype: the decoded video frame is used as a texture
 * and deformed by the quantized MiDaS depth field instead of drawing edge lines.
 */
class DepthMeshPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    private val mesh = MeshView(context)
    private var player: ExoPlayer? = null
    private var sequence: StructureCodec.Sequence? = null
    private var decoder: StructureCodec.PlaybackDecoder? = null
    private var intensity = 0.72f

    init {
        setWillNotDraw(false)
        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(mesh, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
    }

    fun bind(newPlayer: ExoPlayer?, newSequence: StructureCodec.Sequence?, newIntensity: Float) {
        if (player !== newPlayer) {
            player?.clearVideoTextureView(texture)
            player = newPlayer
            if (texture.isAvailable) newPlayer?.setVideoTextureView(texture)
        }
        if (sequence !== newSequence) {
            sequence = newSequence
            decoder = newSequence?.let { StructureCodec.PlaybackDecoder(it) }
        }
        intensity = newIntensity.coerceIn(0f, 1f)
        mesh.bind(texture, player, sequence, decoder, intensity)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        player?.setVideoTextureView(texture)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        player?.clearVideoTextureView(texture)
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        player?.clearVideoTextureView(texture)
        mesh.stop()
        super.onDetachedFromWindow()
    }

    private class MeshView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var texture: TextureView? = null
        private var player: ExoPlayer? = null
        private var sequence: StructureCodec.Sequence? = null
        private var decoder: StructureCodec.PlaybackDecoder? = null
        private var intensity = 0.72f
        private var frame: Bitmap? = null
        private val columns = 32
        private val rows = 18
        private val verts = FloatArray((columns + 1) * (rows + 1) * 2)
        private val smooth = FloatArray(verts.size)
        private var initialized = false

        private val ticker = object : Runnable {
            override fun run() {
                updateMesh()
                if (isAttachedToWindow) postDelayed(this, 42L)
            }
        }

        fun bind(
            texture: TextureView,
            player: ExoPlayer?,
            sequence: StructureCodec.Sequence?,
            decoder: StructureCodec.PlaybackDecoder?,
            intensity: Float,
        ) {
            this.texture = texture
            this.player = player
            this.sequence = sequence
            this.decoder = decoder
            this.intensity = intensity
            removeCallbacks(ticker)
            post(ticker)
        }

        fun stop() {
            removeCallbacks(ticker)
            frame?.recycle()
            frame = null
        }

        private fun updateMesh() {
            val t = texture ?: return
            val p = player ?: return
            val seq = sequence ?: return
            val dec = decoder ?: return
            if (!t.isAvailable || width <= 0 || height <= 0) return
            val next = runCatching { t.getBitmap(320, 180) }.getOrNull() ?: return
            frame?.recycle()
            frame = next
            val state = dec.stateAt(p.currentPosition.coerceAtLeast(0L))
            var out = 0
            for (y in 0..rows) {
                val ny = y / rows.toFloat()
                for (x in 0..columns) {
                    val nx = x / columns.toFloat()
                    val sx = (nx * (seq.width - 1)).toInt().coerceIn(0, seq.width - 1)
                    val sy = (ny * (seq.height - 1)).toInt().coerceIn(0, seq.height - 1)
                    val depth = (state[sy * seq.width + sx].toInt() and 3) / 3f
                    val z = (depth - 0.5f) * intensity
                    // Perspective expansion from screen centre creates textured depth "walls".
                    val scale = 1f + z * 0.16f
                    val px = width * (0.5f + (nx - 0.5f) * scale)
                    val py = height * (0.5f + (ny - 0.5f) * scale) - z * height * 0.025f
                    if (!initialized) {
                        smooth[out] = px
                        smooth[out + 1] = py
                    } else {
                        smooth[out] = smooth[out] * 0.58f + px * 0.42f
                        smooth[out + 1] = smooth[out + 1] * 0.58f + py * 0.42f
                    }
                    verts[out] = smooth[out]
                    verts[out + 1] = smooth[out + 1]
                    out += 2
                }
            }
            initialized = true
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bitmap = frame ?: return
            canvas.drawBitmapMesh(bitmap, columns, rows, verts, 0, null, 0, paint)
        }
    }
}
'''
mesh_path.write_text(mesh_code, encoding="utf-8")

ui = ui_path.read_text(encoding="utf-8")
ui = ui.replace("import com.cardrhyme.sharplayer.player.StructureOverlayView\n", "import com.cardrhyme.sharplayer.player.DepthMeshPlayerView\nimport com.cardrhyme.sharplayer.player.StructureOverlayView\n")
old_surface = '''                AndroidView(
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
'''
new_surface = '''                if (sequence != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> DepthMeshPlayerView(ctx) },
                        update = { reconstructed -> reconstructed.bind(player, sequence, opacity) }
                    )
                } else {
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
                }
'''
if old_surface not in ui:
    raise SystemExit("PlayerSurface anchor not found")
ui = ui.replace(old_surface, new_surface, 1)
ui = ui.replace("Text(\"Line strength\")", "Text(\"Depth reconstruction strength\")")
ui = ui.replace("Option D reconstruction overlay", "Textured depth-mesh reconstruction")
ui_path.write_text(ui, encoding="utf-8")

print("Patched five-second 24 fps textured depth-mesh demo")
