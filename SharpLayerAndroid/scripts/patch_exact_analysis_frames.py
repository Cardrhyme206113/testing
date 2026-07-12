from pathlib import Path
import re

path = Path("SharpLayerAndroid/app/src/main/java/com/cardrhyme/sharplayer/codec/FastStructureAnalyzer.kt")
text = path.read_text(encoding="utf-8")

pattern = re.compile(
    r"    private fun scaledFrame\(\n"
    r"        retriever: MediaMetadataRetriever,\n"
    r"        timeUs: Long,\n"
    r"        width: Int,\n"
    r"        height: Int,\n"
    r"    \): Bitmap\? \{.*?\n"
    r"    \}\n\n"
    r"    /\*\* Returns translation from previous coordinates into current coordinates\. \*/",
    re.DOTALL,
)

replacement = '''    private fun scaledFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        width: Int,
        height: Int,
    ): Bitmap? {
        var decoded: Bitmap? = null
        if (Build.VERSION.SDK_INT >= 27) {
            // Android may preserve aspect ratio and return dimensions a few pixels
            // smaller than requested. Never feed that approximate size into arrays
            // indexed using the requested analysis width/height.
            decoded = retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                width,
                height,
            )
        }
        if (decoded == null) {
            decoded = retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: return null
        }

        val source = decoded
        val exact = if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        if (exact !== source) source.recycle()
        check(exact.width == width && exact.height == height) {
            "Frame normalization failed: ${exact.width}x${exact.height}, expected ${width}x${height}"
        }
        return exact
    }

    /** Returns translation from previous coordinates into current coordinates. */'''

new_text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f"Expected to patch scaledFrame exactly once, patched {count}")

path.write_text(new_text, encoding="utf-8")
print("Patched exact-size analysis frame normalization")
