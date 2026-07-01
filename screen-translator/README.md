# PP-OCRv6 Screen Translator

A separate Android experiment; the existing BlockHost app is not removed or replaced.

## Stack

- Official PaddleOCR Android SDK (ONNX Runtime)
- PP-OCRv6 Small detection + recognition models
- Google ML Kit Japanese → English on-device translation
- MediaProjection screen capture
- `TYPE_APPLICATION_OVERLAY` with `FLAG_NOT_TOUCHABLE` for full touch passthrough

## Behavior

- OCR runs on a throttled loop rather than every video frame.
- Only text containing Japanese kana/kanji is considered.
- Exact and near-identical OCR strings reuse cached translations, so unchanged text is not translated repeatedly.
- The overlay briefly hides before each capture to avoid covering or re-reading itself.
- Google’s translation model downloads once on first use and remains available offline.

## Build

The GitHub Actions workflow clones the current official PaddleOCR repository, injects this module, downloads the PP-OCRv6 Small ONNX assets, builds a debug APK, and uploads it as `ppocrv6-screen-translator-debug`.
