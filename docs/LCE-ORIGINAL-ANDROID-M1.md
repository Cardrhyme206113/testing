# Minecraft LCE — full original-engine Android port (M1)

This branch moves beyond the standalone v0.2 voxel demonstration.

- **Full source-inclusive project:** `LCE-Original-Native-Android-Source-M1.zip` is delivered in the ChatGPT conversation, not checked into this public repository.
- **Actual 4J engine:** CMake builds the original `Minecraft.World`, `Minecraft.Client`, and `Common/UI` C++ targets, based on the existing Fix107 Web port.
- **Android:** EGL-backed GLES3 replaces browser WebGL context setup; Android Java calls original `initialise()` and `frame()` through native JNI; original assets are read from `Common/res` and `Common/Media`.
- **Verified on native Linux Clang:** 710-step `lce_world` build, 407-step client/UI build, full 17-step desktop-link; original media archive loads and actual terrain-generation startup executes.
- **Unresolved:** The native Linux run crashes within original server-chunk/spawn setup after beginning terrain generation, and Android full-engine build is not yet validated. Original RAD Iggy UI still needs a native runtime.

Use the new **LCE ORIGINAL game engine Android ARM64 (source-inclusive)** GitHub Actions workflow with a private HTTPS URL to the complete packaged source ZIP. No synthetic voxel simulation is part of that source's native Android target.

Keep bug reports and native compile failures on this branch; **do not** confuse successful previous prototype APK builds with full-engine validation.
