package dev.card.webstream;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

final class StreamConfig {
    static final int[] ALLOWED_FPS = {30, 40, 50, 60, 90, 120};
    static final int[] ALLOWED_CAPS = {360, 480, 720, 1080, 1440, 2160};

    final String bindHost;
    final int wsPort;
    final int httpPort;
    final String ffmpeg;

    volatile int videoWidth;
    volatile int videoHeight;
    volatile int videoFps;
    volatile int videoBitrateKbps;
    volatile int resolutionCap;
    volatile int gop;
    volatile String videoCodec;

    private final AtomicLong revision = new AtomicLong(1L);

    private StreamConfig(String bindHost, int wsPort, int httpPort, int videoWidth, int videoHeight,
                         int videoFps, int videoBitrateKbps, int resolutionCap, int gop,
                         String videoCodec, String ffmpeg) {
        this.bindHost = bindHost;
        this.wsPort = wsPort;
        this.httpPort = httpPort;
        this.videoWidth = evenAtLeast64(videoWidth);
        this.videoHeight = evenAtLeast64(videoHeight);
        this.videoFps = nearestAllowed(videoFps, ALLOWED_FPS);
        this.videoBitrateKbps = clampBitrate(videoBitrateKbps);
        this.resolutionCap = nearestAllowed(resolutionCap, ALLOWED_CAPS);
        this.gop = Math.max(1, gop);
        this.videoCodec = normalizeCodec(videoCodec);
        this.ffmpeg = ffmpeg;
    }

    static StreamConfig fromEnvironment() {
        int fps = nearestAllowed(envInt("MC_WEBSTREAM_FPS", 60), ALLOWED_FPS);
        return new StreamConfig(
                env("MC_WEBSTREAM_BIND", "0.0.0.0"),
                envInt("MC_WEBSTREAM_WS_PORT", 8765),
                envInt("MC_WEBSTREAM_HTTP_PORT", 8766),
                envInt("MC_WEBSTREAM_WIDTH", 1280),
                envInt("MC_WEBSTREAM_HEIGHT", 720),
                fps,
                envInt("MC_WEBSTREAM_BITRATE_KBPS", 4096),
                envInt("MC_WEBSTREAM_RESOLUTION_CAP", 720),
                envInt("MC_WEBSTREAM_GOP", fps),
                env("MC_WEBSTREAM_CODEC", "av1"),
                env("MC_WEBSTREAM_FFMPEG", "ffmpeg")
        );
    }

    synchronized StreamSettings applySettings(String codec, int fps, int bitrateKbps, int cap) {
        String acceptedCodec = normalizeCodec(codec);
        int acceptedFps = nearestAllowed(fps, ALLOWED_FPS);
        int acceptedBitrate = clampBitrate(bitrateKbps);
        int acceptedCap = nearestAllowed(cap, ALLOWED_CAPS);

        boolean changed = !videoCodec.equals(acceptedCodec)
                || videoFps != acceptedFps
                || videoBitrateKbps != acceptedBitrate
                || resolutionCap != acceptedCap;

        videoCodec = acceptedCodec;
        videoFps = acceptedFps;
        videoBitrateKbps = acceptedBitrate;
        resolutionCap = acceptedCap;
        gop = acceptedFps;

        if (changed) revision.incrementAndGet();
        return snapshot();
    }

    synchronized StreamSettings snapshot() {
        return new StreamSettings(
                revision.get(), videoCodec, videoWidth, videoHeight, videoFps,
                videoBitrateKbps, resolutionCap, gop, maxPixelsForCap(resolutionCap)
        );
    }

    long revision() {
        return revision.get();
    }

    synchronized void setOutputSize(int width, int height) {
        int acceptedWidth = evenAtLeast64(width);
        int acceptedHeight = evenAtLeast64(height);
        if (videoWidth == acceptedWidth && videoHeight == acceptedHeight) return;
        videoWidth = acceptedWidth;
        videoHeight = acceptedHeight;
        revision.incrementAndGet();
    }

    static String normalizeCodec(String codec) {
        if (codec == null) return "av1";
        String normalized = codec.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("hevc") || normalized.equals("h265") || normalized.equals("h.265")
                ? "hevc" : "av1";
    }

    static int clampBitrate(int value) {
        int clamped = Math.max(512, Math.min(8192, value));
        return Math.max(512, Math.min(8192, Math.round(clamped / 512f) * 512));
    }

    static int nearestAllowed(int value, int[] allowed) {
        int best = allowed[0];
        int bestDistance = Math.abs(value - best);
        for (int candidate : allowed) {
            int distance = Math.abs(value - candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    static long maxPixelsForCap(int cap) {
        return switch (nearestAllowed(cap, ALLOWED_CAPS)) {
            case 360 -> 640L * 360L;
            case 480 -> 854L * 480L;
            case 1080 -> 1920L * 1080L;
            case 1440 -> 2560L * 1440L;
            case 2160 -> 3840L * 2160L;
            default -> 1280L * 720L;
        };
    }

    private static int evenAtLeast64(int value) {
        return Math.max(64, value & ~1);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int envInt(String key, int fallback) {
        try {
            return Integer.parseInt(env(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    record StreamSettings(long revision, String codec, int width, int height, int fps,
                          int bitrateKbps, int resolutionCap, int gop, long maxPixels) {
    }
}
