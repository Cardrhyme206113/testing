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
    public double offsetStepBlocks = 2.0;
    public int gridRadius = 1;

    // Pass-level shader reload settle. Depth is intentionally much faster.
    public long beautyShaderSwitchSettleMs = 5000;
    public long depthShaderSwitchSettleMs = 1000;

    // Per-pose target-resolution timing.
    // Beauty: resize -> 3s -> F2 -> 1s -> final screenshot -> restore.
    public long beautyPreF2HighResMs = 3000;
    public long beautyPostF2SettleMs = 1000;
    // Depth does not need temporal accumulation like Bliss.
    public long depthPreF2HighResMs = 0;
    public long depthPostF2SettleMs = 500;

    public long teleportTimeoutMs = 5000;
    public int finalCaptureRenderFrames = 1;
    public boolean warmupF2 = true;
    public boolean keepWarmupImages = true;
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
        c.beautyShaderSwitchSettleMs = beautyShaderSwitchSettleMs;
        c.depthShaderSwitchSettleMs = depthShaderSwitchSettleMs;
        c.beautyPreF2HighResMs = beautyPreF2HighResMs;
        c.beautyPostF2SettleMs = beautyPostF2SettleMs;
        c.depthPreF2HighResMs = depthPreF2HighResMs;
        c.depthPostF2SettleMs = depthPostF2SettleMs;
        c.teleportTimeoutMs = teleportTimeoutMs;
        c.finalCaptureRenderFrames = finalCaptureRenderFrames;
        c.warmupF2 = warmupF2;
        c.keepWarmupImages = keepWarmupImages;
        c.restoreOriginalPosition = restoreOriginalPosition;
        c.restoreOriginalShader = restoreOriginalShader;
        return c;
    }

    public void clamp() {
        captureWidth = clamp(captureWidth, 320, 16384);
        captureHeight = clamp(captureHeight, 180, 16384);
        offsetStepBlocks = clamp(offsetStepBlocks, 0.0, 32.0);
        gridRadius = clamp(gridRadius, 0, 5);
        beautyShaderSwitchSettleMs = clamp(beautyShaderSwitchSettleMs, 0, 60000);
        depthShaderSwitchSettleMs = clamp(depthShaderSwitchSettleMs, 0, 60000);
        beautyPreF2HighResMs = clamp(beautyPreF2HighResMs, 0, 60000);
        beautyPostF2SettleMs = clamp(beautyPostF2SettleMs, 0, 60000);
        depthPreF2HighResMs = clamp(depthPreF2HighResMs, 0, 60000);
        depthPostF2SettleMs = clamp(depthPostF2SettleMs, 0, 60000);
        teleportTimeoutMs = clamp(teleportTimeoutMs, 500, 60000);
        finalCaptureRenderFrames = clamp(finalCaptureRenderFrames, 1, 30);
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
        c.offsetStepBlocks = parseDouble(p, "offset_step_blocks", c.offsetStepBlocks, 0.0, 32.0);
        c.gridRadius = parseInt(p, "grid_radius", c.gridRadius, 0, 5);

        // v0.4 uses new, explicit timing keys. Old ambiguous v0.1-v0.3 timing
        // keys are deliberately not inherited, so upgrading gets the corrected
        // 3s-high-res -> F2 -> 1s-high-res beauty pipeline automatically.
        c.beautyShaderSwitchSettleMs = parseLong(p, "beauty_shader_switch_settle_ms", c.beautyShaderSwitchSettleMs, 0, 60000);
        c.depthShaderSwitchSettleMs = parseLong(p, "depth_shader_switch_settle_ms", c.depthShaderSwitchSettleMs, 0, 60000);
        c.beautyPreF2HighResMs = parseLong(p, "beauty_pre_f2_highres_ms", c.beautyPreF2HighResMs, 0, 60000);
        c.beautyPostF2SettleMs = parseLong(p, "beauty_post_f2_ms", c.beautyPostF2SettleMs, 0, 60000);
        c.depthPreF2HighResMs = parseLong(p, "depth_pre_f2_highres_ms", c.depthPreF2HighResMs, 0, 60000);
        c.depthPostF2SettleMs = parseLong(p, "depth_post_f2_ms", c.depthPostF2SettleMs, 0, 60000);
        c.teleportTimeoutMs = parseLong(p, "teleport_timeout_ms", c.teleportTimeoutMs, 500, 60000);
        c.finalCaptureRenderFrames = parseInt(p, "final_capture_render_frames", c.finalCaptureRenderFrames, 1, 30);
        c.warmupF2 = parseBoolean(p, "warmup_f2", c.warmupF2);

        // F2 images are real useful captures in v0.4. Use a new key so an old
        // keep_warmup_images=false does not silently delete them after upgrade.
        c.keepWarmupImages = parseBoolean(p, "keep_f2_images", c.keepWarmupImages);
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
        p.setProperty("beauty_shader_switch_settle_ms", Long.toString(beautyShaderSwitchSettleMs));
        p.setProperty("depth_shader_switch_settle_ms", Long.toString(depthShaderSwitchSettleMs));
        p.setProperty("beauty_pre_f2_highres_ms", Long.toString(beautyPreF2HighResMs));
        p.setProperty("beauty_post_f2_ms", Long.toString(beautyPostF2SettleMs));
        p.setProperty("depth_pre_f2_highres_ms", Long.toString(depthPreF2HighResMs));
        p.setProperty("depth_post_f2_ms", Long.toString(depthPostF2SettleMs));
        p.setProperty("teleport_timeout_ms", Long.toString(teleportTimeoutMs));
        p.setProperty("final_capture_render_frames", Integer.toString(finalCaptureRenderFrames));
        p.setProperty("warmup_f2", Boolean.toString(warmupF2));
        p.setProperty("keep_f2_images", Boolean.toString(keepWarmupImages));
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
