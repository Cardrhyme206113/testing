# FrameLift Android experiment

A system-overlay frame interpolation prototype for Android 14+ tablets.

Current pipeline:

- user selects a single source app through MediaProjection
- capture arrives directly into a SurfaceTexture / external GLES texture
- full-resolution source frames stay on the GPU
- motion is estimated at 1/8 linear resolution with a GLES 3.1 compute shader
- a second compute pass smooths block vectors
- a full-resolution warp/blend pass presents at the display refresh rate
- source FPS is user-controlled; output is fixed at 120 FPS
- output overlay is touch-through
- a separate secure control bubble and foreground notification remain available for bypass/stop

This first build deliberately uses no neural network. The MediaTek public NPU path does not map classical block matching or warping efficiently, while the Mali GPU can execute the entire pipeline without GPU↔CPU/NPU copies.
