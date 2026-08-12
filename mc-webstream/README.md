# MC WebStream prototype (Fabric 1.20.1)

Experimental hybrid Minecraft streaming client mod:

- **8×8×8 semantic block cache** around the player. A full 512-block snapshot is sent once; normal ticks only send changed/entered blocks.
- **Entity deltas** for nearby entities (position, velocity, rotation, bounds).
- **Remote browser controls** (WASD, jump, sneak, sprint, mouse, attack/use key state).
- **Client-side prediction** in the browser for immediate camera/movement response, with simple collision against the 8³ cache.
- **Nearby WebGL geometry overlay** so fast camera motion has real local 3D structure instead of waiting for the beauty stream.
- **720p60 beauty stream** captured from Minecraft and encoded by FFmpeg. It prefers RTX 40-series `av1_nvenc` and sends AV1/IVF frames directly to browser WebCodecs; CPU VP9 is the fallback.
- Beauty-frame camera pose is sent with every encoded frame so the browser can immediately shift the delayed beauty image toward the predicted view.

This is a proof-of-concept, not a security-hardened remote desktop. It currently binds to the LAN by default and has **no authentication**.

## Build

The current Loom toolchain needs **JDK 21 to run Gradle**. The mod source is still compiled with `--release 17`, so the produced Fabric 1.20.1 mod targets Java 17 bytecode.

```bash
gradle build
```

The jar appears under `build/libs/`.

CI in the parent `testing` repository also builds this folder and uploads the jar.

## Run

1. Put the built jar plus Fabric API in a Fabric **1.20.1** client.
2. Make sure `ffmpeg` is installed and visible in `PATH`.
3. Start Minecraft and enter a world/server.
4. On another device on your LAN open:

```text
http://HOST_PC_IP:8766/
```

5. Click the view to pointer-lock it. Use WASD/mouse normally.

The WebSocket/state/video port is `8765`.

### RTX 4050 / AV1 check

```bash
ffmpeg -hide_banner -encoders | grep av1_nvenc
```

If that encoder exists, the mod uses AV1 NVENC with `p1`, `ull`, CBR, no B-frames, and a 60-frame GOP. Default beauty budget is **2200 kbit/s**.

## Environment knobs

```text
MC_WEBSTREAM_BIND=0.0.0.0
MC_WEBSTREAM_WS_PORT=8765
MC_WEBSTREAM_HTTP_PORT=8766
MC_WEBSTREAM_WIDTH=1280
MC_WEBSTREAM_HEIGHT=720
MC_WEBSTREAM_FPS=60
MC_WEBSTREAM_BITRATE_KBPS=2200
MC_WEBSTREAM_GOP=60
MC_WEBSTREAM_FFMPEG=ffmpeg
```

For an initial low-bandwidth test, try `MC_WEBSTREAM_BITRATE_KBPS=1600`.

## What is intentionally rough in v0.1

- Blocks are represented as simple cubes in the browser; blockstate strings are transmitted but stair/slab/custom model geometry is not reconstructed yet.
- The browser hashes block IDs into rough colors instead of streaming the real resource-pack atlas yet.
- Entity geometry is bounding boxes, not actual entity models.
- Beauty capture currently uses `glReadPixels`; the queue drops frames instead of accumulating latency, but the next performance step is PBO/zero-copy-ish capture.
- Local prediction is approximate. Minecraft remains authoritative and browser state is reconciled toward it.
- Attack/use are currently remote key states; some edge-triggered interactions may need explicit click injection.

Those limitations are deliberate: this version is meant to tell us whether **semantic local rendering + delayed 2-ish Mbit/s beauty** actually feels good before making the capture/render pipeline complicated.
