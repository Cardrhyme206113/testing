# MC WebStream (Fabric 1.20.1)

A simple remote Minecraft stream/control client mod.

The old semantic 3D proxy renderer has been removed. The browser now shows only the real Minecraft framebuffer encoded as AV1.

## Current behavior

- Captures the **entire Minecraft render framebuffer**, including title screen, menus, HUD, inventory/screens, worlds, and servers.
- Streams **AV1 only**.
- Prefers NVIDIA `av1_nvenc`; falls back to `libsvtav1` if NVENC AV1 is unavailable.
- Default output: **1280×720 @ 60 FPS, 4000 kbit/s**.
- Preserves the Minecraft window aspect ratio and pads the encoded 720p frame when needed rather than cropping the game.
- Browser input is forwarded into Minecraft's native keyboard/mouse callbacks.
- A tiny UI-state message tells the browser whether a Minecraft screen is open so it can automatically switch between relative gameplay mouse input and absolute menu cursor input.
- No block geometry cache, entity proxy geometry, prediction renderer, or beauty-image reprojection remains.

This is still a prototype and currently has **no authentication**. Do not expose ports 8765/8766 directly to the public internet.

## Build

The Gradle/Loom toolchain uses JDK 21. The produced Fabric 1.20.1 mod targets Java 17 bytecode.

```bash
gradle build
```

The jar appears under `build/libs/`.

## Run

1. Put the mod jar plus Fabric API into a Fabric 1.20.1 client.
2. Make sure `ffmpeg` is installed and available in `PATH`.
3. Start Minecraft. You do **not** need to enter a world first; menu/title-screen capture works too.
4. Open the viewer:

```text
http://localhost:8766/
```

or from another machine on the LAN:

```text
http://HOST_PC_IP:8766/
```

The browser page uses WebSocket port `8765` automatically.

## AV1 encoder check

```bash
ffmpeg -hide_banner -encoders | grep -E 'av1_nvenc|libsvtav1'
```

On an RTX 40-series machine, `av1_nvenc` is preferred. NVENC uses a `p4` preset, ultra-low-latency tuning, CBR, no B-frames, and the configured GOP.

## Environment settings

Defaults:

```text
MC_WEBSTREAM_BIND=0.0.0.0
MC_WEBSTREAM_WS_PORT=8765
MC_WEBSTREAM_HTTP_PORT=8766
MC_WEBSTREAM_WIDTH=1280
MC_WEBSTREAM_HEIGHT=720
MC_WEBSTREAM_FPS=60
MC_WEBSTREAM_BITRATE_KBPS=4000
MC_WEBSTREAM_GOP=60
MC_WEBSTREAM_FFMPEG=ffmpeg
```

Example: 1080p60 at 8 Mbit/s:

```bash
MC_WEBSTREAM_WIDTH=1920 \
MC_WEBSTREAM_HEIGHT=1080 \
MC_WEBSTREAM_FPS=60 \
MC_WEBSTREAM_BITRATE_KBPS=8000 \
java ...
```

Set the environment variables on the Minecraft/launcher process before starting the game.

## Remaining rough edge

Framebuffer readback currently uses synchronous `glReadPixels`. Frames are dropped instead of queued when capture/encoding falls behind, so latency does not intentionally accumulate, but a PBO-based readback path would reduce render-thread stalls later.
