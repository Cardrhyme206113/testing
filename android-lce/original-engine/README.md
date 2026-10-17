# M1: Original 4J world classes, native ARM64

This directory is the start of the **real engine port**, not another imitation game.

The original supplied source is never committed to this public repository.
The staging script privately copies the five actual 4J world classes
`Random`, `ImprovedNoise`, `PerlinNoise`, `Mth`, `Synth`. It replaces only
the MSVC-specific precompiled-header include and an otherwise unused
`System.h` include in `Random.cpp`. SHA-256 checksums for every original
input are kept in `STAGING-MANIFEST.json`.

## Stage from original user's unpacked source
```bash
python3 android-lce/original-engine/stage-original-4j.py \
  --source-root /private/Minecraft-original-root \
  --destination android-lce/original-engine/staged
```
The root must contain `Minecraft.World`. Never `git add` that directory.

## Build host regression
```bash
cd android-lce/original-engine
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_PROJECT_INCLUDE_BEFORE=./CMakeLists.host.txt
```
Alternatively use the private local proof package's top-level CMakeLists.txt.

## Build Android
The main app's CMake automatically detects staged original sources and links
the 4J world-core static library into `liblceandroid.so`. The app exposes an
actual original noise reference-vector probe, distinguishing this from the
fallback prototype.

This proof only ports five foundational engine classes. It is **not** the
original Level, ChunkSource, entity simulation, menu system, or renderer yet.

Continue in dependency order: original LevelChunk and Level generation
dependencies; shared Minecraft.World; Minecraft.Client platform services;
original tile/entity renderer using GLES3; game loop; Iggy menus.
