# LCE Native Android - Phase 1 (textured world prototype)

**Status:** Real Android ARM64/GLES3 APK with first-person textured voxel interaction. **Not** the original complete Minecraft LCE engine yet. No Wine/Winlator, WebView, WebAssembly or Java Minecraft renderer. Native C++ generates the mesh, runs the physics and performs the GL draw calls.

## Where the authentic LCE parts come from

The user supplied `source.7z`, `MediaWindows64.arc` and on 2026-09-19 `res.zip`. Original files are **not copied into this public repository**. The app imports resources from Android's system file picker.

- Import **res.zip** after installing to get `res/TitleUpdate/res/terrain.png`, `res/gui/gui.png`, `res/gui/icons.png`, replacing fallback textures and hotbar art.
- Optionally import **MediaWindows64.arc** to inspect the original 4J UI SWF index; it does not yet execute SWF menus on Android.
- The native block IDs and terrain atlas indices reflect the classic original source (`Minecraft.World/Tile.cpp`, `GrassTile.cpp`), but current world generation and physics are a new compatibility prototype, not a reuse of the original `Level.cpp`.

## Phase 1 implemented

- OpenGL ES 3.0 world mesh with hidden-face culling and nearest-filtered original terrain atlas.
- Seeded 96×40×96 editable voxel level with grass/dirt/stone/sand, water and trees.
- First-person perspective and ground collision, jumps, break/place block rays.
- Multitouch move/drag/jump/break/place and a nine-slot touch hotbar.
- Xbox-compatible controller axis/button input and keyboard number keys.
- Original 4J GUI texture crop and crosshair when `res.zip` is imported.
- Native local save/load at `native_world_v02.dat`. This format **is not an LCE world save**.
- Archive reader and world CPU unit tests in GitHub Actions.

## Next real-engine-port stages

1. Bring actual shared `Minecraft.World` code and original noise, blocks, chunks into a standalone ARM64 target. Adapt Windows macro and subsystem assumptions.
2. Drive GLES with 4J's `TileRenderer` geometry and original `RenderManager` calls, replacing synthetic mesh generation.
3. Replace prototype player physics with actual `Minecraft.Client` game ticks and entities.
4. Adapt the real `Minecraft.Client/Common/UI` controller and Iggy/SWF calls to a native Android UI runtime (largest UI blocker).
5. Use the original audio banks, world serialisation, menu flows and inventory logic.
6. Profile Mali/Immortalis frame pacing and optimise draw submission.

## Build

Use GitHub Actions workflow `LCE Native Android - textured world v0.2` or Android Studio with Java 17 / AGP 8.9.2 / NDK 27.2 / SDK 35. For ABI `arm64-v8a`. App ID is `dev.card.lceandroid.world` to coexist with previous cube prototype.

Generated/debug APKs are signed by CI and may have differing signing keys on later runs; uninstall an older native-world test APK before installing another if Android reports a package conflict. Uninstalling deletes its local test world. Avoid using this as the only copy of important creations.
