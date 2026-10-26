from pathlib import Path

path = Path("SharpLayerAndroid/app/src/main/java/com/cardrhyme/sharplayer/player/DepthMeshPlayerView.kt")
text = path.read_text(encoding="utf-8")

text = text.replace(
'''    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        player?.clearVideoTextureView(texture)
        return true
    }
''',
'''    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Stop frame capture before the backing SurfaceTexture disappears. Do not
        // clear ExoPlayer here: fullscreen creates the replacement TextureView
        // immediately, and clearing the old one can detach the newly-bound surface.
        mesh.stopTickerOnly()
        return true
    }
''')

text = text.replace(
'''    override fun onDetachedFromWindow() {
        player?.clearVideoTextureView(texture)
        mesh.stop()
        super.onDetachedFromWindow()
    }
''',
'''    override fun onDetachedFromWindow() {
        // The fullscreen Compose branch replaces this view. Stop all callbacks first
        // and let the next available TextureView replace ExoPlayer's output surface.
        mesh.stop()
        super.onDetachedFromWindow()
    }
''')

text = text.replace(
'''        fun stop() {
            removeCallbacks(ticker)
            frame?.recycle()
            frame = null
        }
''',
'''        fun stopTickerOnly() {
            removeCallbacks(ticker)
        }

        fun stop() {
            removeCallbacks(ticker)
            frame?.recycle()
            frame = null
            initialized = false
        }
''')

old = '''            if (!t.isAvailable || width <= 0 || height <= 0) return
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
'''
new = '''            if (!t.isAvailable || width <= 0 || height <= 0 || !isAttachedToWindow) return

            val videoSize = p.videoSize
            val rawVideoWidth = videoSize.width.takeIf { it > 0 } ?: seq.width
            val rawVideoHeight = videoSize.height.takeIf { it > 0 } ?: seq.height
            val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
            val videoAspect = (rawVideoWidth * pixelRatio / rawVideoHeight.toFloat()).coerceAtLeast(0.01f)
            val viewAspect = width / height.toFloat()

            val contentLeft: Float
            val contentTop: Float
            val contentWidth: Float
            val contentHeight: Float
            if (viewAspect > videoAspect) {
                contentHeight = height.toFloat()
                contentWidth = contentHeight * videoAspect
                contentLeft = (width - contentWidth) * 0.5f
                contentTop = 0f
            } else {
                contentWidth = width.toFloat()
                contentHeight = contentWidth / videoAspect
                contentLeft = 0f
                contentTop = (height - contentHeight) * 0.5f
            }

            // Capture at the real display aspect. Never force portrait or ultrawide
            // footage through a 16:9 320x180 bitmap.
            val captureWidth: Int
            val captureHeight: Int
            if (videoAspect >= 1f) {
                captureWidth = 320
                captureHeight = (captureWidth / videoAspect).toInt().coerceIn(1, 320)
            } else {
                captureHeight = 320
                captureWidth = (captureHeight * videoAspect).toInt().coerceIn(1, 320)
            }
            val next = runCatching { t.getBitmap(captureWidth, captureHeight) }.getOrNull() ?: return
            val oldFrame = frame
            frame = next
            oldFrame?.recycle()

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
                    // Perspective expansion is constrained to the fitted video rect,
                    // preserving the source aspect ratio and its black bars.
                    val scale = 1f + z * 0.16f
                    val px = contentLeft + contentWidth * (0.5f + (nx - 0.5f) * scale)
                    val py = contentTop + contentHeight * (0.5f + (ny - 0.5f) * scale) - z * contentHeight * 0.025f
'''
if old not in text:
    raise SystemExit("Depth mesh update anchor not found")
text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Fixed depth mesh aspect ratio and fullscreen surface lifecycle")
