package dev.card.parallaxcapture;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class CaptureConfig {
    public String beautyShader = "@current";
    public String depthShader = "PUT_DEPTH_SHADER_FILENAME_HERE.zip";
    public int captureWidth = 3840;
    public int captureHeight = 2160;
    public double offsetStepBlocks = 0.20;
    public int gridRadius = 1;
    public long warmupDelayMs = 1000;
    public long settleAfterWarmupMs = 5000;
    public long shaderSwitchSettleMs = 5000;
    public long teleportTimeoutMs = 5000;
    public int highResRenderFrames = 3;
    public boolean warmupF2 = true;
    public boolean keepWarmupImages = false;
    public boolean restoreOriginalPosition = true;
    public boolean restoreOriginalShader = true;

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("parallax-capture.properties");

    public static CaptureConfig defaults() {
        return new CaptureConfig();
    }

    public CaptureConfig copy() {
        CaptureConfig c = new CaptureConfig();
        c.beautyShader = beautyShader;
        c.depthShader = depthShader;
        c.captureWidth = captureWidth;
        c.captureHeight = captureHeight;
        c.offsetStepBlocks = offsetStepBlocks;
        c.gridRadius = gridRadius;
        c.warmupDelayMs = warmupDelayMs;
        c.settleAfterWarmupMs = settleAfterWarmupMs;
        c.shaderSwitchSettleMs = shaderSwitchSettleMs;
        c.teleportTimeoutMs = teleportTimeoutMs;
        c.highResRenderFrames = highResRenderFrames;
        c.warmupF2 = warmupF2;
        c.keepWarmupImages = keepWarmupImages;
        c.restoreOriginalPosition = restoreOriginalPosition;
        c.restoreOriginalShader = restoreOriginalShader;
        return c;
    }

    public void clamp() {
        captureWidth = clamp(captureWidth, 320, 16384);
        captureHeight = clamp(captureHeight, 180, 16384);
        offsetStepBlocks = clamp(offsetStepBlocks, 0.0, 16.0);
        gridRadius = clamp(gridRadius, 0, 5);
        warmupDelayMs = clamp(warmupDelayMs, 0, 60000);
        settleAfterWarmupMs = clamp(settleAfterWarmupMs, 0, 60000);
        shaderSwitchSettleMs = clamp(shaderSwitchSettleMs, 0, 60000);
        teleportTimeoutMs = clamp(teleportTimeoutMs, 500, 60000);
        highResRenderFrames = clamp(highResRenderFrames, 1, 30);
        beautyShader = beautyShader == null || beautyShader.isBlank() ? "@current" : beautyShader.trim();
        depthShader = depthShader == null ? "" : depthShader.trim();
    }

    public static CaptureConfig load() {
        CaptureConfig c = new CaptureConfig();
        Properties p = new Properties();

        if (Files.exists(PATH)) {
            try (BufferedReader r = Files.newBufferedReader(PATH)) {
                p.load(r);
            } catch (IOException e) {
                ParallaxCaptureClient.LOGGER.warn("Could not read {}", PATH, e);
            }
        }

        c.beautyShader = p.getProperty("beauty_shader", c.beautyShader).trim();
        c.depthShader = p.getProperty("depth_shader", c.depthShader).trim();
        c.captureWidth = parseInt(p, "capture_width", c.captureWidth, 320, 16384);
        c.captureHeight = parseInt(p, "capture_height", c.captureHeight, 180, 16384);
        c.offsetStepBlocks = parseDouble(p, "offset_step_blocks", c.offsetStepBlocks, 0.0, 16.0);
        c.gridRadius = parseInt(p, "grid_radius", c.gridRadius, 0, 5);
        c.warmupDelayMs = parseLong(p, "warmup_delay_ms", c.warmupDelayMs, 0, 60000);
        c.settleAfterWarmupMs = parseLong(p, "settle_after_warmup_ms", c.settleAfterWarmupMs, 0, 60000);
        c.shaderSwitchSettleMs = parseLong(p, "shader_switch_settle_ms", c.shaderSwitchSettleMs, 0, 60000);
        c.teleportTimeoutMs = parseLong(p, "teleport_timeout_ms", c.teleportTimeoutMs, 500, 60000);
        c.highResRenderFrames = parseInt(p, "high_res_render_frames", c.highResRenderFrames, 1, 30);
        c.warmupF2 = parseBoolean(p, "warmup_f2", c.warmupF2);
        c.keepWarmupImages = parseBoolean(p, "keep_warmup_images", c.keepWarmupImages);
        c.restoreOriginalPosition = parseBoolean(p, "restore_original_position", c.restoreOriginalPosition);
        c.restoreOriginalShader = parseBoolean(p, "restore_original_shader", c.restoreOriginalShader);
        c.clamp();
        c.save();
        return c;
    }

    public void save() {
        clamp();
        Properties p = new Properties();
        p.setProperty("beauty_shader", beautyShader);
        p.setProperty("depth_shader", depthShader);
        p.setProperty("capture_width", Integer.toString(captureWidth));
        p.setProperty("capture_height", Integer.toString(captureHeight));
        p.setProperty("offset_step_blocks", Double.toString(offsetStepBlocks));
        p.setProperty("grid_radius", Integer.toString(gridRadius));
        p.setProperty("warmup_delay_ms", Long.toString(warmupDelayMs));
        p.setProperty("settle_after_warmup_ms", Long.toString(settleAfterWarmupMs));
        p.setProperty("shader_switch_settle_ms", Long.toString(shaderSwitchSettleMs));
        p.setProperty("teleport_timeout_ms", Long.toString(teleportTimeoutMs));
        p.setProperty("high_res_render_frames", Integer.toString(highResRenderFrames));
        p.setProperty("warmup_f2", Boolean.toString(warmupF2));
        p.setProperty("keep_warmup_images", Boolean.toString(keepWarmupImages));
        p.setProperty("restore_original_position", Boolean.toString(restoreOriginalPosition));
        p.setProperty("restore_original_shader", Boolean.toString(restoreOriginalShader));

        try {
            Files.createDirectories(PATH.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(PATH)) {
                p.store(w, "Parallax Capture 1.20.1 - normally edit this through Mod Menu > Parallax Capture > Configure");
            }
        } catch (IOException e) {
            ParallaxCaptureClient.LOGGER.warn("Could not save {}", PATH, e);
        }
    }

    private static int parseInt(Properties p, String k, int d, int min, int max) {
        try { return clamp(Integer.parseInt(p.getProperty(k, Integer.toString(d)).trim()), min, max); }
        catch (Exception ignored) { return d; }
    }
    private static long parseLong(Properties p, String k, long d, long min, long max) {
        try { return clamp(Long.parseLong(p.getProperty(k, Long.toString(d)).trim()), min, max); }
        catch (Exception ignored) { return d; }
    }
    private static double parseDouble(Properties p, String k, double d, double min, double max) {
        try { return clamp(Double.parseDouble(p.getProperty(k, Double.toString(d)).trim()), min, max); }
        catch (Exception ignored) { return d; }
    }
    private static boolean parseBoolean(Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        return v == null ? d : Boolean.parseBoolean(v.trim());
    }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static long clamp(long v, long min, long max) { return Math.max(min, Math.min(max, v)); }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
