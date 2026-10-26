package dev.cardrhyme.equirectshot;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class CaptureManager {
    public static final CaptureManager INSTANCE = new CaptureManager();

    private final int[][] faces = new int[6][];
    private boolean active;
    private boolean waitingForReadback;
    private boolean parallelFaces;
    private int parallelReadbacks;
    private int face;
    private int renderedFrames;
    private long faceStartNanos;
    private int outputWidth;
    private int outputHeight;
    private int faceSize;
    private double settleSeconds;
    private Entity cameraEntity;
    private float originalXRot;
    private float originalYRot;
    private float originalXRotO;
    private float originalYRotO;
    private int oldWindowWidth;
    private int oldWindowHeight;
    private int oldTargetWidth;
    private int oldTargetHeight;
    private boolean oldHideGui;

    private CaptureManager() {}

    public boolean isActive() {
        return active;
    }

    public void start(Minecraft client) {
        if (active) return;
        if (client.level == null || client.getCameraEntity() == null) {
            EquirectShotClient.overlay(client, "EquirectShot: enter a world first");
            return;
        }
        if (client.screen != null) {
            EquirectShotClient.overlay(client, "EquirectShot: close menus before capturing");
            return;
        }

        EquirectShotClient.CONFIG.sanitize();
        outputWidth = EquirectShotClient.CONFIG.outputWidth();
        outputHeight = EquirectShotClient.CONFIG.outputHeight();
        faceSize = EquirectShotClient.CONFIG.faceSize();
        settleSeconds = EquirectShotClient.CONFIG.settleSeconds;
        parallelFaces = EquirectShotClient.CONFIG.parallelFaces;

        cameraEntity = client.getCameraEntity();
        originalXRot = cameraEntity.getXRot();
        originalYRot = cameraEntity.getYRot();
        originalXRotO = cameraEntity.xRotO;
        originalYRotO = cameraEntity.yRotO;

        RenderTarget target = client.getMainRenderTarget();
        oldWindowWidth = client.getWindow().getWidth();
        oldWindowHeight = client.getWindow().getHeight();
        oldTargetWidth = target.width;
        oldTargetHeight = target.height;
        oldHideGui = client.options.hideGui;

        client.options.hideGui = true;
        client.gameRenderer.setRenderBlockOutline(false);
        client.gameRenderer.setPanoramicMode(true);
        client.getWindow().setWidth(faceSize);
        client.getWindow().setHeight(faceSize);
        target.resize(faceSize, faceSize);

        for (int i = 0; i < faces.length; i++) faces[i] = null;
        face = 0;
        parallelReadbacks = 0;
        renderedFrames = 0;
        waitingForReadback = false;
        faceStartNanos = System.nanoTime();
        active = true;

        if (parallelFaces) {
            EquirectShotClient.overlay(client, settleSeconds > 0.0
                    ? "EquirectShot: waiting for same-frame capture"
                    : "EquirectShot: preparing same-frame capture");
        } else {
            EquirectShotClient.overlay(client, "EquirectShot: capturing 1/6");
        }
    }

    public void beforeRenderLevel(Minecraft client) {
        if (!active || parallelFaces || cameraEntity == null) return;
        applyFaceRotation(face);
    }

    public void afterRenderLevel(Minecraft client) {
        if (!active || parallelFaces || cameraEntity == null) return;
        restoreEntityRotation();
    }

    public void afterRender(Minecraft client) {
        if (!active || waitingForReadback) return;

        if (parallelFaces) {
            afterRenderParallel(client);
            return;
        }

        renderedFrames++;
        double elapsed = (System.nanoTime() - faceStartNanos) / 1_000_000_000.0;
        if (renderedFrames < 2 || elapsed < settleSeconds) return;

        waitingForReadback = true;
        int capturedFace = face;
        Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
            try {
                int w = image.getWidth();
                int h = image.getHeight();
                if (w != faceSize || h != faceSize) {
                    throw new IllegalStateException("Expected " + faceSize + "x" + faceSize + " framebuffer, got " + w + "x" + h);
                }
                int[] pixels = image.getPixels();
                client.execute(() -> faceReady(client, capturedFace, pixels));
            } catch (Throwable t) {
                client.execute(() -> fail(client, t));
            } finally {
                image.close();
            }
        });
    }

    private void afterRenderParallel(Minecraft client) {
        renderedFrames++;
        double elapsed = (System.nanoTime() - faceStartNanos) / 1_000_000_000.0;
        if (renderedFrames < 2 || elapsed < settleSeconds) return;

        waitingForReadback = true;
        parallelReadbacks = 0;
        EquirectShotClient.overlay(client, "EquirectShot: capturing all 6 faces");

        try {
            for (int i = 0; i < 6; i++) {
                renderFaceNow(client, i);
                queueParallelReadback(client, i);
            }
        } catch (Throwable t) {
            fail(client, t);
        } finally {
            if (cameraEntity != null) restoreEntityRotation();
        }
    }

    private void renderFaceNow(Minecraft client, int faceIndex) {
        applyFaceRotation(faceIndex);
        try {
            client.gameRenderer.renderLevel(DeltaTracker.ONE);
        } finally {
            restoreEntityRotation();
        }
    }

    private void queueParallelReadback(Minecraft client, int capturedFace) {
        Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
            try {
                int w = image.getWidth();
                int h = image.getHeight();
                if (w != faceSize || h != faceSize) {
                    throw new IllegalStateException("Expected " + faceSize + "x" + faceSize + " framebuffer, got " + w + "x" + h);
                }
                int[] pixels = image.getPixels();
                client.execute(() -> parallelFaceReady(client, capturedFace, pixels));
            } catch (Throwable t) {
                client.execute(() -> fail(client, t));
            } finally {
                image.close();
            }
        });
    }

    private void parallelFaceReady(Minecraft client, int capturedFace, int[] pixels) {
        if (!active || !parallelFaces) return;
        if (capturedFace < 0 || capturedFace >= faces.length || faces[capturedFace] != null) return;

        faces[capturedFace] = pixels;
        parallelReadbacks++;
        if (parallelReadbacks == 6) finishCapture(client);
    }

    private void faceReady(Minecraft client, int capturedFace, int[] pixels) {
        if (!active || capturedFace != face) return;
        faces[capturedFace] = pixels;
        waitingForReadback = false;
        if (face == 5) {
            finishCapture(client);
            return;
        }
        face++;
        renderedFrames = 0;
        faceStartNanos = System.nanoTime();
        EquirectShotClient.overlay(client, "EquirectShot: capturing " + (face + 1) + "/6");
    }

    private void finishCapture(Minecraft client) {
        int[][] completedFaces = new int[6][];
        System.arraycopy(faces, 0, completedFaces, 0, faces.length);
        int completedFaceSize = faceSize;
        int completedWidth = outputWidth;
        int completedHeight = outputHeight;
        restore(client);

        Path screenshotDir = client.gameDirectory.toPath().resolve("screenshots");
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(screenshotDir);
                Path output = EquirectStitcher.stitch(completedFaces, completedFaceSize, completedWidth, completedHeight, screenshotDir);
                client.execute(() -> EquirectShotClient.overlay(client, "EquirectShot saved: " + output.getFileName()));
            } catch (Throwable t) {
                EquirectShotClient.LOGGER.error("Failed to stitch equirectangular screenshot", t);
                client.execute(() -> EquirectShotClient.overlay(client, "EquirectShot failed: " + safeMessage(t)));
            }
        });
    }

    public void cancel(Minecraft client, String reason) {
        if (!active) return;
        restore(client);
        EquirectShotClient.overlay(client, "EquirectShot: " + reason);
    }

    private void fail(Minecraft client, Throwable t) {
        if (!active) return;
        EquirectShotClient.LOGGER.error("EquirectShot capture failed", t);
        restore(client);
        EquirectShotClient.overlay(client, "EquirectShot failed: " + safeMessage(t));
    }

    private void restore(Minecraft client) {
        if (!active) return;
        active = false;
        waitingForReadback = false;
        if (cameraEntity != null) restoreEntityRotation();
        try {
            client.gameRenderer.setPanoramicMode(false);
            client.gameRenderer.setRenderBlockOutline(true);
            client.getWindow().setWidth(oldWindowWidth);
            client.getWindow().setHeight(oldWindowHeight);
            client.getMainRenderTarget().resize(oldTargetWidth, oldTargetHeight);
            client.options.hideGui = oldHideGui;
        } finally {
            cameraEntity = null;
            parallelFaces = false;
            parallelReadbacks = 0;
        }
    }

    private void applyFaceRotation(int faceIndex) {
        float yaw = originalYRot;
        float pitch = 0.0F;
        switch (faceIndex) {
            case 1 -> yaw = originalYRot + 90.0F;
            case 2 -> yaw = originalYRot + 180.0F;
            case 3 -> yaw = originalYRot - 90.0F;
            case 4 -> pitch = -90.0F;
            case 5 -> pitch = 90.0F;
            default -> { }
        }
        cameraEntity.setYRot(yaw);
        cameraEntity.setXRot(pitch);
        cameraEntity.yRotO = yaw;
        cameraEntity.xRotO = pitch;
    }

    private void restoreEntityRotation() {
        if (cameraEntity == null) return;
        cameraEntity.setXRot(originalXRot);
        cameraEntity.setYRot(originalYRot);
        cameraEntity.xRotO = originalXRotO;
        cameraEntity.yRotO = originalYRotO;
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
