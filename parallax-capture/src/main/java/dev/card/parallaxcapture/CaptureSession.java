package dev.card.parallaxcapture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CaptureSession {
    private enum State {
        BEGIN_PASS, WAIT_SHADER, SEND_TELEPORT, WAIT_ARRIVAL, WAIT_WARMUP, WAIT_SETTLE,
        RESIZE_FOR_CAPTURE, HIGH_RES_RENDER, ADVANCE, FINISHING, DONE
    }

    private record Pose(int index, int rightIndex, int upIndex, double x, double y, double z, float yaw, float pitch) {}
    private record Pass(String folder, String shader) {}

    private final MinecraftClient client;
    private final CaptureConfig cfg;
    private final IrisBridge iris;
    private final List<Pose> poses = new ArrayList<>();
    private final List<String> metadataCaptures = new ArrayList<>();
    private final List<Pass> passes = new ArrayList<>();

    private State state = State.BEGIN_PASS;
    private long deadlineMs;
    private long teleportSentMs;
    private int passIndex;
    private int poseIndex;
    private int highResFrames;
    private boolean cancelled;

    private final double originalX, originalY, originalZ;
    private final float originalYaw, originalPitch;
    private final int originalFov;
    private final String originalShader;
    private final boolean originalShadersEnabled;
    private final Path sessionDir;
    private final String relativeSessionDir;

    private int oldWidth, oldHeight;
    private boolean oldHudHidden;
    private boolean resized;

    public CaptureSession(MinecraftClient client, CaptureConfig cfg) throws Exception {
        this.client = client;
        this.cfg = cfg;
        if (client.player == null || client.world == null) throw new IllegalStateException("Join a world first");

        this.iris = new IrisBridge();
        this.originalX = client.player.getX();
        this.originalY = client.player.getY();
        this.originalZ = client.player.getZ();
        this.originalYaw = client.player.getYaw();
        this.originalPitch = client.player.getPitch();
        this.originalFov = client.options.getFov().getValue();
        this.originalShader = iris.configuredPackName();
        this.originalShadersEnabled = iris.shadersEnabled();

        String beauty = "@current".equalsIgnoreCase(cfg.beautyShader) ? originalShader : cfg.beautyShader;
        if (beauty == null || beauty.isBlank() || "(off)".equals(beauty)) {
            throw new IllegalStateException("No beauty shader is active. Select one in Iris first or set beauty_shader in config.");
        }
        validateShaderExists(beauty, "beauty");
        validateShaderExists(cfg.depthShader, "depth");
        passes.add(new Pass("beauty", beauty));
        passes.add(new Pass("depth", cfg.depthShader));

        buildPoses();

        String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").format(LocalDateTime.now());
        relativeSessionDir = "parallax_capture/" + stamp;
        sessionDir = client.runDirectory.toPath().resolve("screenshots").resolve(relativeSessionDir);
        Files.createDirectories(sessionDir.resolve("beauty"));
        Files.createDirectories(sessionDir.resolve("depth"));
        Files.createDirectories(sessionDir.resolve("_warmup"));
        writeMetadata(false);
    }

    private void validateShaderExists(String name, String label) {
        if (name == null || name.isBlank() || name.startsWith("PUT_")) {
            throw new IllegalStateException("Set " + label + "_shader in config/parallax-capture.properties first.");
        }
        Path p = client.runDirectory.toPath().resolve("shaderpacks").resolve(name);
        if (!Files.exists(p)) {
            throw new IllegalStateException("Iris " + label + " shader not found: shaderpacks/" + name);
        }
    }

    private void buildPoses() {
        double yawRad = Math.toRadians(originalYaw);
        // Minecraft yaw 0 faces +Z; positive yaw turns toward -X.
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        int idx = 0;
        poses.add(new Pose(idx++, 0, 0, originalX, originalY, originalZ, originalYaw, originalPitch));
        for (int up = cfg.gridRadius; up >= -cfg.gridRadius; up--) {
            for (int right = -cfg.gridRadius; right <= cfg.gridRadius; right++) {
                if (right == 0 && up == 0) continue;
                double dx = rightX * (right * cfg.offsetStepBlocks);
                double dz = rightZ * (right * cfg.offsetStepBlocks);
                double dy = up * cfg.offsetStepBlocks;
                poses.add(new Pose(idx++, right, up, originalX + dx, originalY + dy, originalZ + dz, originalYaw, originalPitch));
            }
        }
    }

    public boolean isDone() { return state == State.DONE; }

    public void cancel() {
        cancelled = true;
        state = State.FINISHING;
        client.player.sendMessage(Text.literal("Parallax capture cancelling…").formatted(Formatting.YELLOW), true);
    }

    public void onPreRender() {
        if (state == State.DONE) return;
        long now = System.currentTimeMillis();

        try {
            switch (state) {
                case BEGIN_PASS -> beginPass(now);
                case WAIT_SHADER -> { if (now >= deadlineMs) state = State.SEND_TELEPORT; }
                case SEND_TELEPORT -> sendTeleport(now);
                case WAIT_ARRIVAL -> waitArrival(now);
                case WAIT_WARMUP -> { if (now >= deadlineMs) doWarmup(now); }
                case WAIT_SETTLE -> { if (now >= deadlineMs) state = State.RESIZE_FOR_CAPTURE; }
                case RESIZE_FOR_CAPTURE -> resizeForCapture();
                case ADVANCE -> advance();
                case FINISHING -> finish();
                default -> {}
            }
        } catch (Throwable t) {
            ParallaxCaptureClient.LOGGER.error("Capture session failed", t);
            client.player.sendMessage(Text.literal("Parallax capture failed: " + rootMessage(t)).formatted(Formatting.RED), false);
            cancelled = true;
            state = State.FINISHING;
        }
    }

    public void onPostRender() {
        if (state != State.HIGH_RES_RENDER) return;
        highResFrames++;
        if (highResFrames < cfg.highResRenderFrames) return;

        try {
            Pose p = poses.get(poseIndex);
            Pass pass = passes.get(passIndex);
            String file = String.format(Locale.ROOT, "%s/pose_%02d_r%+d_u%+d.png", pass.folder(), p.index(), p.rightIndex(), p.upIndex());
            ScreenshotRecorder.saveScreenshot(
                    client.runDirectory,
                    relativeSessionDir + "/" + file,
                    client.getFramebuffer(),
                    ignored -> {}
            );
            metadataCaptures.add(captureJson(pass, p, file));
        } catch (Throwable t) {
            ParallaxCaptureClient.LOGGER.error("High-resolution screenshot failed", t);
            client.player.sendMessage(Text.literal("Screenshot failed: " + rootMessage(t)).formatted(Formatting.RED), false);
            cancelled = true;
        } finally {
            restoreResolution();
            state = cancelled ? State.FINISHING : State.ADVANCE;
        }
    }

    private void beginPass(long now) throws Exception {
        if (passIndex >= passes.size()) {
            state = State.FINISHING;
            return;
        }
        Pass pass = passes.get(passIndex);
        client.player.sendMessage(Text.literal("Parallax: loading " + pass.folder() + " shader — " + pass.shader()).formatted(Formatting.AQUA), true);
        iris.switchTo(pass.shader());
        deadlineMs = now + cfg.shaderSwitchSettleMs;
        poseIndex = 0;
        state = State.WAIT_SHADER;
    }

    private void sendTeleport(long now) {
        Pose p = poses.get(poseIndex);
        String command = String.format(Locale.ROOT, "tp @s %.6f %.6f %.6f %.4f %.4f", p.x(), p.y(), p.z(), p.yaw(), p.pitch());
        client.player.networkHandler.sendChatCommand(command);
        teleportSentMs = now;
        state = State.WAIT_ARRIVAL;
    }

    private void waitArrival(long now) {
        Pose p = poses.get(poseIndex);
        double dx = client.player.getX() - p.x();
        double dy = client.player.getY() - p.y();
        double dz = client.player.getZ() - p.z();
        boolean arrived = dx * dx + dy * dy + dz * dz < 0.01;
        if (arrived || now - teleportSentMs >= cfg.teleportTimeoutMs) {
            deadlineMs = now + cfg.warmupDelayMs;
            state = State.WAIT_WARMUP;
        }
    }

    private void doWarmup(long now) throws IOException {
        Pose p = poses.get(poseIndex);
        Pass pass = passes.get(passIndex);
        if (cfg.warmupF2) {
            String name = String.format(Locale.ROOT, "%s_%02d_r%+d_u%+d.png", pass.folder(), p.index(), p.rightIndex(), p.upIndex());
            ScreenshotRecorder.saveScreenshot(
                    client.runDirectory,
                    relativeSessionDir + "/_warmup/" + name,
                    client.getFramebuffer(),
                    ignored -> {}
            );
        }
        // Bliss warm-up requested by the capture workflow: TP -> 1s -> F2-style shot -> ~5s settle.
        deadlineMs = now + cfg.settleAfterWarmupMs;
        state = State.WAIT_SETTLE;
    }

    private void resizeForCapture() {
        oldWidth = client.getWindow().getWidth();
        oldHeight = client.getWindow().getHeight();
        oldHudHidden = client.options.hudHidden;
        client.options.hudHidden = true;
        MinecraftInterface.resize(cfg.captureWidth, cfg.captureHeight);
        resized = true;
        highResFrames = 0;
        state = State.HIGH_RES_RENDER;
    }

    private void restoreResolution() {
        if (!resized) return;
        try {
            MinecraftInterface.resize(oldWidth, oldHeight);
        } finally {
            client.options.hudHidden = oldHudHidden;
            resized = false;
        }
    }

    private void advance() {
        poseIndex++;
        if (poseIndex >= poses.size()) {
            passIndex++;
            state = State.BEGIN_PASS;
        } else {
            state = State.SEND_TELEPORT;
        }
    }

    private void finish() {
        restoreResolution();
        try {
            if (cfg.restoreOriginalPosition && client.player != null) {
                String command = String.format(Locale.ROOT, "tp @s %.6f %.6f %.6f %.4f %.4f", originalX, originalY, originalZ, originalYaw, originalPitch);
                client.player.networkHandler.sendChatCommand(command);
            }
            if (cfg.restoreOriginalShader) {
                iris.restore(originalShader, originalShadersEnabled);
            }
            writeMetadata(true);
            if (!cfg.keepWarmupImages) deleteWarmupDirectory();
        } catch (Throwable t) {
            ParallaxCaptureClient.LOGGER.warn("Could not fully restore capture state", t);
        }

        if (client.player != null) {
            String msg = cancelled ? "Parallax capture cancelled." : "Parallax capture complete: screenshots/" + relativeSessionDir;
            client.player.sendMessage(Text.literal(msg).formatted(cancelled ? Formatting.YELLOW : Formatting.GREEN), false);
        }
        state = State.DONE;
    }

    private void writeMetadata(boolean complete) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"complete\": ").append(complete && !cancelled).append(",\n");
        sb.append("  \"cancelled\": ").append(cancelled).append(",\n");
        sb.append("  \"resolution\": [").append(cfg.captureWidth).append(',').append(cfg.captureHeight).append("],\n");
        sb.append("  \"fov\": ").append(originalFov).append(",\n");
        sb.append("  \"basePose\": {\"x\":").append(originalX).append(",\"y\":").append(originalY).append(",\"z\":").append(originalZ)
                .append(",\"yaw\":").append(originalYaw).append(",\"pitch\":").append(originalPitch).append("},\n");
        sb.append("  \"offsetStepBlocks\": ").append(cfg.offsetStepBlocks).append(",\n");
        sb.append("  \"gridRadius\": ").append(cfg.gridRadius).append(",\n");
        sb.append("  \"warmupDelayMs\": ").append(cfg.warmupDelayMs).append(",\n");
        sb.append("  \"settleAfterWarmupMs\": ").append(cfg.settleAfterWarmupMs).append(",\n");
        sb.append("  \"beautyShader\": ").append(json(passes.isEmpty() ? null : passes.get(0).shader())).append(",\n");
        sb.append("  \"depthShader\": ").append(json(passes.size() < 2 ? cfg.depthShader : passes.get(1).shader())).append(",\n");
        sb.append("  \"captures\": [\n");
        for (int i = 0; i < metadataCaptures.size(); i++) {
            sb.append("    ").append(metadataCaptures.get(i));
            if (i + 1 < metadataCaptures.size()) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        try {
            Files.writeString(sessionDir.resolve("metadata.json"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ParallaxCaptureClient.LOGGER.warn("Could not write capture metadata", e);
        }
    }

    private String captureJson(Pass pass, Pose p, String file) {
        return "{\"pass\":" + json(pass.folder()) + ",\"shader\":" + json(pass.shader()) +
                ",\"poseIndex\":" + p.index() + ",\"rightIndex\":" + p.rightIndex() + ",\"upIndex\":" + p.upIndex() +
                ",\"x\":" + p.x() + ",\"y\":" + p.y() + ",\"z\":" + p.z() +
                ",\"yaw\":" + p.yaw() + ",\"pitch\":" + p.pitch() + ",\"file\":" + json(file) + "}";
    }

    private void deleteWarmupDirectory() {
        Path dir = sessionDir.resolve("_warmup");
        try {
            if (!Files.exists(dir)) return;
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                });
            }
        } catch (IOException ignored) {}
    }

    private static String json(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
