# Minecraft LCE — native Android port of the original engine

This branch targets the actual **Fix107 LCE Web C++ game engine**, not the synthetic v0.2 voxel demonstration.

## Source-inclusive artifact

The ChatGPT delivery `LCE-Original-Native-Android-Source-M1.zip` contains the complete original C++ project, existing Web platform code, the recovered media and original resource textures, plus:

- Original `Minecraft.World`, `Minecraft.Client` and `Common/UI` CMake targets reused.
- Android/ARM64 shared-library target instead of a browser executable.
- GLES3 rendering path reusing `Web_Render.cpp`, with EGL owned by `GLSurfaceView`.
- JNI exposing the **original** `initialise()` and `frame()` functions.
- Input mapped to original Xbox controller button and axis states.
- Import logic for original `res.zip` and `MediaWindows64.arc` into their expected directory structure.
- A native 64-bit pointer-size fix in `ServerChunkCache.cpp` and `MultiPlayerChunkCache.cpp`.

Source is inside the downloadable private ZIP. It is not published into this public repository.

## Verified results: real source, Clang on Linux x86-64

- **710-step original `lce_world` build: PASS**
- **407-step shared client/UI build: PASS**
- **17-step original game/platform executable link: PASS**
- Original media archive loads.
- Original world-generation code runs and initializes host/client gameplay.
- **`SetGameStarted - true`** and **`game world is live; entering render loop`** confirmed.

The native smoke test initially crashed during the original server chunk cache's post-processing. Root cause was `#if defined(_WEB)` selecting a 32-bit pointer atomic CAS on a 64-bit native host. Restricting that branch to `__EMSCRIPTEN__` fixed the crash without changing the original algorithm or WASM behavior.

**Still NOT verified:** Full-source NDK ARM64 compilation/linking and Android device rendering. No playable original-engine APK is claimed. The original Iggy/SWF menu runtime, full native audio and stable Android graphics thread behavior are later milestones.

## CI

Run [LCE ORIGINAL game engine Android ARM64 (source-inclusive)](../.github/workflows/lce-android-full-original-engine.yml) with the **full original ZIP** at a private HTTPS download URL, stored in the repository secret `LCE_ANDROID_FULL_SOURCE_URL` or supplied to workflow dispatch. CI deliberately fails if a complete original source tree is missing; it will never substitute the voxel demo and label it a port.
