# LCE Native Android — phase 0 bootstrap

This is **not a playable Minecraft port yet**. It is a genuine ARM64 C++/OpenGL ES3 Android application (no WebView, Wine, Box64 or WASM). It demonstrates the native render loop and can import `MediaWindows64.arc` using Android's system file picker, parsing the original 4J ArchiveFile header in native C++.

Original game source and media assets are deliberately **not committed** to this public repository. Reuse the original source privately for the later game-engine migration. The existing LCE web branches are untouched.

Build with JDK 17, SDK 35, NDK 27.2.12479018, CMake 3.22.1 and Gradle 8.11.1, or download the unsigned-for-store but debug-signed APK from the **LCE Android native ARM64 bootstrap** Actions artifact.

Next milestones: original game-loop entry point, port RenderManager to GLES3, Android controller/touch input, Java/Ruffle-compatible UI replacement, native audio/saving, and real-device profiling on Immortalis/Mali GPU.
