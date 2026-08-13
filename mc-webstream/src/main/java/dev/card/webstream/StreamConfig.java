package dev.card.webstream;

final class StreamConfig {
    final String bindHost;
    final int wsPort;
    final int httpPort;
    volatile int videoWidth;
    volatile int videoHeight;
    final int videoFps;
    final int videoBitrateKbps;
    final int gop;
    final String ffmpeg;

    private StreamConfig(String bindHost, int wsPort, int httpPort, int videoWidth, int videoHeight,
                         int videoFps, int videoBitrateKbps, int gop, String ffmpeg) {
        this.bindHost = bindHost;
        this.wsPort = wsPort;
        this.httpPort = httpPort;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoFps = videoFps;
        this.videoBitrateKbps = videoBitrateKbps;
        this.gop = gop;
        this.ffmpeg = ffmpeg;
    }

    static StreamConfig fromEnvironment() {
        return new StreamConfig(
                env("MC_WEBSTREAM_BIND", "0.0.0.0"),
                envInt("MC_WEBSTREAM_WS_PORT", 8765),
                envInt("MC_WEBSTREAM_HTTP_PORT", 8766),
                envInt("MC_WEBSTREAM_WIDTH", 1280),
                envInt("MC_WEBSTREAM_HEIGHT", 720),
                envInt("MC_WEBSTREAM_FPS", 60),
                envInt("MC_WEBSTREAM_BITRATE_KBPS", 4000),
                envInt("MC_WEBSTREAM_GOP", 60),
                env("MC_WEBSTREAM_FFMPEG", "ffmpeg")
        );
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
}
