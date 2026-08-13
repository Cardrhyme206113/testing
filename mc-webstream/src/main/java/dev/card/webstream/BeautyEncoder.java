package dev.card.webstream;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

final class BeautyEncoder implements AutoCloseable {
    private final StreamConfig config;
    private final WebStreamServer server;
    private final BlockingQueue<RawFrame> queue = new ArrayBlockingQueue<>(2);
    private final ConcurrentLinkedQueue<ByteBuffer> bufferPool = new ConcurrentLinkedQueue<>();
    private final Thread encoderThread;

    private volatile boolean running = true;
    private volatile Process process;
    private volatile int sourceWidth = -1;
    private volatile int sourceHeight = -1;
    private volatile EncoderChoice selectedEncoder;
    private volatile Boolean useFlatpakHostFfmpeg;
    private long lastCaptureNs;
    private long nextEncoderRetryNs;
    private boolean loggedFirstCapture;
    private boolean loggedFirstEncoded;
    private boolean loggedReadError;

    BeautyEncoder(StreamConfig config, WebStreamServer server) {
        this.config = config;
        this.server = server;
        encoderThread = new Thread(this::encoderLoop, "mc-webstream-encoder");
        encoderThread.setDaemon(true);
        encoderThread.start();
    }

    /** Capture the actual default framebuffer backbuffer immediately before GLFW swaps it. */
    void captureBackbufferIfNeeded(int width, int height) {
        if (!running || !server.hasClients()) return;

        long now = System.nanoTime();
        long frameInterval = 1_000_000_000L / Math.max(1, config.videoFps);
        if (now - lastCaptureNs < frameInterval) return;
        lastCaptureNs = now;

        if (width <= 0 || height <= 0) return;

        if (width != sourceWidth || height != sourceHeight) {
            sourceWidth = width;
            sourceHeight = height;
            queue.clear();
            bufferPool.clear();
            stopProcess();
            McWebStreamClient.LOGGER.info("WebStream capture source is now {}x{}", width, height);
        }

        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        ByteBuffer buffer = takeBuffer(bytes);
        if (buffer == null) return;

        int oldReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int oldPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int oldPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int oldPackRowLength = GL11.glGetInteger(GL12.GL_PACK_ROW_LENGTH);
        int oldPackSkipRows = GL11.glGetInteger(GL12.GL_PACK_SKIP_ROWS);
        int oldPackSkipPixels = GL11.glGetInteger(GL12.GL_PACK_SKIP_PIXELS);

        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL12.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL12.GL_PACK_SKIP_PIXELS, 0);

            buffer.clear();
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                bufferPool.offer(buffer);
                if (!loggedReadError) {
                    loggedReadError = true;
                    McWebStreamClient.LOGGER.error("Final backbuffer glReadPixels failed with GL error 0x{}",
                            Integer.toHexString(error));
                }
                return;
            }

            buffer.position(0);
            buffer.limit(bytes);
            if (!loggedFirstCapture) {
                loggedFirstCapture = true;
                McWebStreamClient.LOGGER.info("WebStream captured first final-window frame ({} bytes)", bytes);
            }

            if (!queue.offer(new RawFrame(width, height, buffer))) bufferPool.offer(buffer);
        } catch (Throwable t) {
            bufferPool.offer(buffer);
            McWebStreamClient.LOGGER.warn("Final-window capture failed", t);
        } finally {
            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldReadFramebuffer);
                GL11.glReadBuffer(oldReadBuffer);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPixelPackBuffer);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, oldPackAlignment);
                GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, oldPackRowLength);
                GL11.glPixelStorei(GL12.GL_PACK_SKIP_ROWS, oldPackSkipRows);
                GL11.glPixelStorei(GL12.GL_PACK_SKIP_PIXELS, oldPackSkipPixels);
            } catch (Throwable ignored) {
            }
        }
    }

    private ByteBuffer takeBuffer(int bytes) {
        ByteBuffer b;
        while ((b = bufferPool.poll()) != null) if (b.capacity() == bytes) return b;
        if (queue.remainingCapacity() == 0) return null;
        return ByteBuffer.allocateDirect(bytes);
    }

    private void encoderLoop() {
        while (running) {
            EncoderChoice choice = null;
            try {
                RawFrame first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                if (System.nanoTime() < nextEncoderRetryNs) {
                    bufferPool.offer(first.data);
                    continue;
                }

                choice = chooseEncoder();
                if (choice == null) {
                    bufferPool.offer(first.data);
                    nextEncoderRetryNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    continue;
                }

                startEncoder(first.width, first.height, choice);
                writeFrames(first);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                McWebStreamClient.LOGGER.warn("AV1 encoder loop failed{}", choice == null ? "" : " (" + choice + ")", t);
                selectedEncoder = null;
                stopProcess();
                nextEncoderRetryNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            }
        }
    }

    private void writeFrames(RawFrame first) throws IOException, InterruptedException {
        Process current = process;
        if (current == null) return;

        try (WritableByteChannel stdin = Channels.newChannel(current.getOutputStream())) {
            RawFrame frame = first;
            while (running && process == current && current.isAlive()) {
                if (frame == null) {
                    frame = queue.poll(250, TimeUnit.MILLISECONDS);
                    continue;
                }
                if (frame.width != sourceWidth || frame.height != sourceHeight) {
                    bufferPool.offer(frame.data);
                    break;
                }

                try {
                    while (frame.data.hasRemaining()) stdin.write(frame.data);
                } finally {
                    frame.data.clear();
                    bufferPool.offer(frame.data);
                }
                frame = queue.poll(250, TimeUnit.MILLISECONDS);
            }
        } finally {
            stopProcess();
        }
    }

    private void startEncoder(int inputWidth, int inputHeight, EncoderChoice choice) throws IOException {
        stopProcess();

        List<String> c = ffmpegCommand();
        c.add("-hide_banner");
        c.add("-loglevel");
        c.add("warning");
        c.add("-f");
        c.add("rawvideo");
        c.add("-pix_fmt");
        c.add("rgba");
        c.add("-video_size");
        c.add(inputWidth + "x" + inputHeight);
        c.add("-framerate");
        c.add(Integer.toString(config.videoFps));
        c.add("-i");
        c.add("pipe:0");
        c.add("-an");

        String filter = "vflip,scale=" + config.videoWidth + ":" + config.videoHeight +
                ":force_original_aspect_ratio=decrease:flags=fast_bilinear,pad=" +
                config.videoWidth + ":" + config.videoHeight + ":(ow-iw)/2:(oh-ih)/2:black";
        c.add("-vf");
        c.add(filter);
        addEncoderArgs(c, choice);
        c.add("-flush_packets");
        c.add("1");
        c.add("-f");
        c.add("ivf");
        c.add("pipe:1");

        McWebStreamClient.LOGGER.info("Starting AV1 stream encoder [{}]: {}", choice, String.join(" ", c));
        Process started = new ProcessBuilder(c).start();
        process = started;

        Thread stderr = new Thread(() -> streamStderr(started.getErrorStream()), "mc-webstream-ffmpeg-log");
        stderr.setDaemon(true);
        stderr.start();

        Thread output = new Thread(() -> readIvf(started.getInputStream(), choice, inputWidth, inputHeight),
                "mc-webstream-video-out");
        output.setDaemon(true);
        output.start();
    }

    private void addEncoderArgs(List<String> c, EncoderChoice choice) {
        switch (choice) {
            case NVENC_LL, NVENC_HQ, NVENC_BASIC -> {
                c.add("-c:v"); c.add("av1_nvenc");
                c.add("-preset"); c.add("p4");
                if (choice == EncoderChoice.NVENC_LL) { c.add("-tune"); c.add("ull"); }
                if (choice == EncoderChoice.NVENC_HQ) { c.add("-tune"); c.add("hq"); }
                if (choice != EncoderChoice.NVENC_BASIC) { c.add("-rc"); c.add("cbr"); }
                c.add("-b:v"); c.add(config.videoBitrateKbps + "k");
                c.add("-maxrate"); c.add(config.videoBitrateKbps + "k");
                c.add("-bufsize"); c.add((config.videoBitrateKbps * 2) + "k");
                c.add("-g"); c.add(Integer.toString(config.gop));
                c.add("-bf"); c.add("0");
                c.add("-pix_fmt"); c.add("nv12");
            }
            case SVT_AV1 -> {
                c.add("-c:v"); c.add("libsvtav1");
                c.add("-preset"); c.add("10");
                c.add("-b:v"); c.add(config.videoBitrateKbps + "k");
                c.add("-g"); c.add(Integer.toString(config.gop));
                c.add("-pix_fmt"); c.add("yuv420p");
            }
            case AOM_AV1 -> {
                c.add("-c:v"); c.add("libaom-av1");
                c.add("-usage"); c.add("realtime");
                c.add("-cpu-used"); c.add("8");
                c.add("-row-mt"); c.add("1");
                c.add("-lag-in-frames"); c.add("0");
                c.add("-error-resilient"); c.add("1");
                c.add("-threads"); c.add(Integer.toString(Math.max(2, Runtime.getRuntime().availableProcessors() / 2)));
                c.add("-b:v"); c.add(config.videoBitrateKbps + "k");
                c.add("-g"); c.add(Integer.toString(config.gop));
                c.add("-pix_fmt"); c.add("yuv420p");
            }
        }
    }

    private void readIvf(InputStream raw, EncoderChoice choice, int inputWidth, int inputHeight) {
        try (BufferedInputStream in = new BufferedInputStream(raw, 1 << 20)) {
            byte[] header = readExactly(in, 32);
            if (header == null || header[0] != 'D' || header[1] != 'K' || header[2] != 'I' || header[3] != 'F') {
                throw new IOException("FFmpeg did not emit IVF");
            }

            server.sendVideoConfig(codecString(), config.videoWidth, config.videoHeight,
                    config.videoFps, config.videoBitrateKbps, inputWidth, inputHeight);

            long frameIndex = 0;
            while (running) {
                byte[] fh = readExactly(in, 12);
                if (fh == null) break;
                int size = le32(fh, 0);
                if (size <= 0 || size > 16 * 1024 * 1024) throw new IOException("Invalid IVF frame size: " + size);
                byte[] payload = readExactly(in, size);
                if (payload == null) break;

                long ts = frameIndex * 1_000_000L / Math.max(1, config.videoFps);
                boolean key = frameIndex % Math.max(1, config.gop) == 0;
                server.sendVideoFrame(choice.codecId, key, ts, payload);
                if (!loggedFirstEncoded) {
                    loggedFirstEncoded = true;
                    McWebStreamClient.LOGGER.info("WebStream emitted first AV1 frame ({} bytes, encoder={})",
                            payload.length, choice);
                }
                frameIndex++;
            }
        } catch (IOException e) {
            if (running) McWebStreamClient.LOGGER.warn("AV1 output ended: {}", e.toString());
        }
    }

    private String codecString() {
        if (config.videoWidth > 1920 || config.videoHeight > 1080 || config.videoFps > 120) return "av01.0.12M.08";
        if (config.videoWidth > 1280 || config.videoHeight > 720 || config.videoFps > 30) return "av01.0.08M.08";
        return "av01.0.05M.08";
    }

    private EncoderChoice chooseEncoder() {
        EncoderChoice cached = selectedEncoder;
        if (cached != null) return cached;

        String encoders;
        try {
            List<String> c = ffmpegCommand();
            c.add("-hide_banner");
            c.add("-encoders");
            Process probe = new ProcessBuilder(c).redirectErrorStream(true).start();
            encoders = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (!probe.waitFor(3, TimeUnit.SECONDS)) probe.destroyForcibly();
        } catch (Exception e) {
            McWebStreamClient.LOGGER.warn("Could not execute FFmpeg '{}': {}", config.ffmpeg, e.toString());
            return null;
        }

        if (encoders.contains("av1_nvenc")) {
            for (EncoderChoice candidate : new EncoderChoice[]{EncoderChoice.NVENC_LL, EncoderChoice.NVENC_HQ, EncoderChoice.NVENC_BASIC}) {
                ProbeResult result = probeEncoder(candidate);
                if (result.ok) {
                    selectedEncoder = candidate;
                    McWebStreamClient.LOGGER.info("AV1 encoder probe selected {}", candidate);
                    return candidate;
                }
                McWebStreamClient.LOGGER.warn("AV1 encoder probe rejected {}: {}", candidate, result.message);
            }
            McWebStreamClient.LOGGER.warn("av1_nvenc is listed by FFmpeg but cannot initialize; falling back to software AV1");
        }

        if (encoders.contains("libsvtav1")) {
            ProbeResult result = probeEncoder(EncoderChoice.SVT_AV1);
            if (result.ok) {
                selectedEncoder = EncoderChoice.SVT_AV1;
                McWebStreamClient.LOGGER.info("Using libsvtav1 fallback because NVENC AV1 is unavailable at runtime");
                return selectedEncoder;
            }
            McWebStreamClient.LOGGER.warn("AV1 encoder probe rejected SVT_AV1: {}", result.message);
        }

        if (encoders.contains("libaom-av1")) {
            ProbeResult result = probeEncoder(EncoderChoice.AOM_AV1);
            if (result.ok) {
                selectedEncoder = EncoderChoice.AOM_AV1;
                McWebStreamClient.LOGGER.warn("Using libaom-av1 realtime fallback; this can use substantial CPU");
                return selectedEncoder;
            }
            McWebStreamClient.LOGGER.warn("AV1 encoder probe rejected AOM_AV1: {}", result.message);
        }

        McWebStreamClient.LOGGER.warn("FFmpeg has no usable AV1 encoder (tried av1_nvenc, libsvtav1, libaom-av1)");
        return null;
    }

    private ProbeResult probeEncoder(EncoderChoice choice) {
        List<String> c = ffmpegCommand();
        c.add("-hide_banner");
        c.add("-loglevel");
        c.add("error");
        c.add("-f");
        c.add("lavfi");
        c.add("-i");
        c.add("color=c=black:s=" + config.videoWidth + "x" + config.videoHeight + ":r=" + config.videoFps);
        c.add("-frames:v");
        c.add("1");
        addEncoderArgs(c, choice);
        c.add("-f");
        c.add("null");
        c.add("-");

        try {
            Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
            boolean exited = p.waitFor(6, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return new ProbeResult(false, "probe timed out");
            }
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.exitValue() == 0) return new ProbeResult(true, "ok");
            if (text.isEmpty()) text = "FFmpeg exit " + p.exitValue();
            text = text.replace('\n', ' ').replace('\r', ' ');
            if (text.length() > 700) text = text.substring(0, 700);
            return new ProbeResult(false, text);
        } catch (Exception e) {
            return new ProbeResult(false, e.toString());
        }
    }

    /**
     * Flatpak has its own /usr and therefore its own FFmpeg. On systems where that
     * runtime FFmpeg requires a newer NVENC API than the installed NVIDIA driver,
     * use the host FFmpeg through flatpak-spawn when the permission is available.
     */
    private List<String> ffmpegCommand() {
        List<String> c = new ArrayList<>();
        if (shouldUseFlatpakHostFfmpeg()) {
            c.add("flatpak-spawn");
            c.add("--host");
            c.add(hostFfmpegPath());
        } else {
            c.add(config.ffmpeg);
        }
        return c;
    }

    private boolean shouldUseFlatpakHostFfmpeg() {
        String flatpakId = System.getenv("FLATPAK_ID");
        if (flatpakId == null || flatpakId.isBlank()) return false;

        Boolean cached = useFlatpakHostFfmpeg;
        if (cached != null) return cached;

        synchronized (this) {
            cached = useFlatpakHostFfmpeg;
            if (cached != null) return cached;

            String hostFfmpeg = hostFfmpegPath();
            try {
                Process probe = new ProcessBuilder("flatpak-spawn", "--host", hostFfmpeg, "-version")
                        .redirectErrorStream(true).start();
                boolean exited = probe.waitFor(3, TimeUnit.SECONDS);
                if (!exited) {
                    probe.destroyForcibly();
                    useFlatpakHostFfmpeg = false;
                    McWebStreamClient.LOGGER.warn("Flatpak host FFmpeg probe timed out; using sandbox FFmpeg");
                    return false;
                }
                String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (probe.exitValue() == 0) {
                    useFlatpakHostFfmpeg = true;
                    String firstLine = output.lines().findFirst().orElse(hostFfmpeg);
                    McWebStreamClient.LOGGER.info("Flatpak detected; using host FFmpeg via flatpak-spawn: {}", firstLine);
                    return true;
                }
                useFlatpakHostFfmpeg = false;
                McWebStreamClient.LOGGER.warn("Flatpak host FFmpeg unavailable; using sandbox FFmpeg: {}",
                        output.replace('\n', ' ').replace('\r', ' '));
                return false;
            } catch (Exception e) {
                useFlatpakHostFfmpeg = false;
                McWebStreamClient.LOGGER.warn("Could not invoke host FFmpeg through flatpak-spawn; using sandbox FFmpeg: {}",
                        e.toString());
                return false;
            }
        }
    }

    private String hostFfmpegPath() {
        if (config.ffmpeg == null || config.ffmpeg.isBlank() || "ffmpeg".equals(config.ffmpeg)) return "/usr/bin/ffmpeg";
        return config.ffmpeg;
    }

    private void streamStderr(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) McWebStreamClient.LOGGER.warn("FFmpeg: {}", line);
            }
        } catch (IOException ignored) {
        }
    }

    private static byte[] readExactly(InputStream in, int size) throws IOException {
        byte[] out = new byte[size];
        int offset = 0;
        while (offset < size) {
            int n = in.read(out, offset, size - offset);
            if (n < 0) return null;
            offset += n;
        }
        return out;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }

    private synchronized void stopProcess() {
        Process p = process;
        process = null;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(250, TimeUnit.MILLISECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        running = false;
        encoderThread.interrupt();
        stopProcess();
    }

    private record RawFrame(int width, int height, ByteBuffer data) {}
    private record ProbeResult(boolean ok, String message) {}

    private enum EncoderChoice {
        NVENC_LL((byte) 1),
        NVENC_HQ((byte) 1),
        NVENC_BASIC((byte) 1),
        SVT_AV1((byte) 1),
        AOM_AV1((byte) 1);

        final byte codecId;
        EncoderChoice(byte codecId) { this.codecId = codecId; }
    }
}
