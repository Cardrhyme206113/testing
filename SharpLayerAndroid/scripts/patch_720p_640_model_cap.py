from pathlib import Path

engine_path = Path("SharpLayerAndroid/app/src/main/java/com/cardrhyme/sharplayer/export/OptionDExportEngine.kt")
ui_path = Path("SharpLayerAndroid/app/src/main/java/com/cardrhyme/sharplayer/ui/SharpLayerApp.kt")
models_path = Path("SharpLayerAndroid/app/src/main/java/com/cardrhyme/sharplayer/codec/FastVisionModels.kt")

engine = engine_path.read_text(encoding="utf-8")
engine = engine.replace("val outputHeight: Int = 1080,", "val outputHeight: Int = 720,")
engine = engine.replace(
    "        val target = settings.totalBitrateKbps.coerceIn(120, 4_000)\n",
    "        val target = settings.totalBitrateKbps.coerceIn(120, 4_000)\n        val outputHeight = 720 // Fixed fast base-video resolution.\n",
)
engine = engine.replace("height = settings.outputHeight,", "height = outputHeight,")
engine = engine.replace("${settings.outputHeight}p", "${outputHeight}p")
engine = engine.replace('.put("outputHeight", settings.outputHeight)', '.put("outputHeight", outputHeight)')
engine_path.write_text(engine, encoding="utf-8")

ui = ui_path.read_text(encoding="utf-8")
ui = ui.replace("var targetHeight by remember { mutableIntStateOf(1080) }", "var targetHeight by remember { mutableIntStateOf(720) }")
old_slider = '''                Slider(
                    value = targetHeight.toFloat(),
                    onValueChange = {
                        onHeightChanged(((it / 120f).roundToInt() * 120).coerceIn(360, 1080))
                    },
                    valueRange = 360f..1080f,
                    steps = 5,
                    enabled = !running
                )
'''
new_slider = '''                Text(
                    "Fixed at 720p for faster decoding and encoding.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
'''
if old_slider not in ui:
    raise SystemExit("Output-height slider anchor not found")
ui = ui.replace(old_slider, new_slider)
ui_path.write_text(ui, encoding="utf-8")

models = models_path.read_text(encoding="utf-8")
models = models.replace(
    "class FastVisionModels(context: Context) : Closeable {\n",
    "class FastVisionModels(context: Context) : Closeable {\n    private companion object { const val MAX_MODEL_SOURCE_LONG_EDGE = 640 }\n",
)
old_bitmap_input = '''        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
'''
new_bitmap_input = '''        // Cap only oversized source frames. Never enlarge a smaller source to 640p;
        // the final resize below is solely to the model's fixed native tensor shape.
        val source = if (max(bitmap.width, bitmap.height) > MAX_MODEL_SOURCE_LONG_EDGE) {
            val scale = MAX_MODEL_SOURCE_LONG_EDGE.toFloat() / max(bitmap.width, bitmap.height).toFloat()
            val cappedWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val cappedHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, cappedWidth, cappedHeight, true)
        } else {
            bitmap
        }
        val scaled = if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        if (source !== bitmap) source.recycle()
'''
if old_bitmap_input not in models:
    raise SystemExit("Model bitmap-input anchor not found")
models = models.replace(old_bitmap_input, new_bitmap_input)
models_path.write_text(models, encoding="utf-8")

print("Patched fixed 720p output and downscale-only 640px model source cap")
