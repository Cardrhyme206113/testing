# Parallax Capture — integrated stock test

This branch packages the recovered Fabric 1.20.1 Parallax Capture v0.5.2 surface/DH capture flow with the verified v5.7 Native Fast converter integrated into the mod.

Current test goals:

- F8 starts/cancels the normal 3x3 capture.
- The depth and surface Iris exporter shaderpacks are bundled by the mod and same-named copies in `.minecraft/shaderpacks/` are overwritten from the current JAR.
- The user's currently active Iris shader remains the beauty pass and is restored afterward.
- A Minecraft HUD panel reports capture and conversion progress but is hidden during actual capture frames so it is never baked into scene textures.
- Conversion starts automatically with the validated v5.7 defaults.
- The converter is kept as readable bundled Python source and runs through a persistent warmed worker; there is no separate server UI/launch step.
- Raw capture screenshots are deleted only after successful scene conversion. Failed/cancelled captures are kept for recovery/debugging.
- Default capture is 2560x1440, 3x3, 0.5-block offsets, JPEG quality 95.

The temporary `project.tgz.b64.part*` files are the complete buildable source snapshot used by CI while this integration is being validated. GitHub Actions reconstructs it and publishes the Fabric JAR artifact.
