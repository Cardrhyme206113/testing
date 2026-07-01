# PP-OCRv6 Screen Translator

A separate Android experiment; the existing BlockHost app is not removed or replaced.

## Stack

- Official PaddleOCR Android SDK using ONNX Runtime
- PP-OCRv6 Tiny detection and recognition models
- Google ML Kit Japanese to English on-device translation
- MediaProjection screen capture
- Touch-through application overlay

## Behavior

- Every OCR region is displayed.
- Japanese regions are covered with their English translation.
- Other detected text receives a yellow translucent highlight without changing the text.
- A green status pill reports total OCR regions and Japanese regions.
- OCR runs on a throttled loop rather than every video frame.
- Exact and near-identical Japanese strings reuse cached translations.
- The overlay briefly hides before capture so it never recognizes itself.
- Screen capture permission must be approved again after restarting the app or phone.
- Google's translation model downloads once and remains available offline.

## Build

The arm64 PP-OCRv6 Tiny APK is kept below 40 MB and published through GitHub Releases.
