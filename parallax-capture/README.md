# Parallax Capture (Fabric 1.20.1)

Client-side automation for paired beauty/depth multi-view captures intended for parallax wallpapers.

## Intended flow
1. Pick your beauty shader/preset in Iris.
2. Let normal + Distant Horizons chunks finish loading.
3. Set the depth shader's exact zip/folder filename in `config/parallax-capture.properties`.
4. Look exactly where you want the wallpaper centered. Your current FOV/yaw/pitch are preserved.
5. Press **F8** (rebindable in Controls > Parallax Capture).
6. The mod captures the same camera-plane grid twice: beauty pass, then depth pass.

For each camera pose it does:
- OP `/tp @s ... yaw pitch` to the exact pose.
- Wait for the teleport to actually arrive.
- Wait **1 second**.
- Trigger a normal Minecraft screenshot (F2-style) to push shaders such as Bliss into screenshot/render behavior.
- Wait **5 more seconds** for temporal/shader settling.
- Hide HUD, resize Minecraft's framebuffer to the configured resolution (default **3840x2160**), render a few frames, save PNG.

It restores the original viewport/HUD, position, and Iris shader when finished.

## Config
Generated at `config/parallax-capture.properties`:

```properties
beauty_shader=@current
depth_shader=PUT_DEPTH_SHADER_FILENAME_HERE.zip
capture_width=3840
capture_height=2160
offset_step_blocks=0.20
grid_radius=1
warmup_delay_ms=1000
settle_after_warmup_ms=5000
shader_switch_settle_ms=5000
teleport_timeout_ms=5000
high_res_render_frames=3
warmup_f2=true
keep_warmup_images=false
restore_original_position=true
restore_original_shader=true
```

`grid_radius=1` = 3x3 = 9 poses per shader (18 final PNGs). `offset_step_blocks` is the spacing in the camera plane. The center pose is always captured first.

Outputs live in `screenshots/parallax_capture/<timestamp>/` with `beauty/`, `depth/`, and `metadata.json`.

## Requirements
- Minecraft 1.20.1
- Fabric Loader + Fabric API
- Iris (runtime, for shader switching)
- Permission to run `/tp` (`/op` in multiplayer or cheats enabled in singleplayer)

## Notes
- This does **not** wait for Distant Horizons to generate; start it only after your scene is fully loaded, as intended.
- Warm-up screenshots are placed in `_warmup/` and deleted at the end by default.
- The shader-switch integration uses Iris's runtime methods reflectively so this mod doesn't hard-depend on an Iris jar at compile time.
