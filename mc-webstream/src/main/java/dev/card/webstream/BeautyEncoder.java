package dev.card.webstream;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private long lastCaptureNs;
    private long nextEncoderRetryNs;

    BeautyEncoder(StreamConfig config, WebStreamServer server) {
        this.config = config;
        this.server = server;
        encoderThread = new Thread(this::encoderLoop, "mc-webstream-encoder");
        encoderThread.setDaemon(true);
        encoderThread.start();
    }

    void captureIfNeeded(MinecraftClient client) {
        if (!running || !server.hasClients()) return;

        long now = System.nanoTime();
        long frameInterval = 1_000_000_000L / Math.max(1, config.videoFps);
        if (now - lastCaptureNs < frameInterval) return;
        lastCaptureNs = now;

        int width = client.getWindow().getFramebufferWidth();
        int height = client.getWindow().getFramebufferHeight();
        if (width <= 0 || height <= 0) return;

        if (width != sourceWidth || height != sourceHeight) {
            sourceWidth = width;
            sourceHeight = height;
            queue.clear();
            bufferPool.clear();
            stopProcess();
        }

        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        ByteBuffer buffer = takeBuffer(bytes);
        if (buffer == null) return;

        try {
            Framebuffer framebuffer = client.getFramebuffer();
            framebuffer.beginWrite(false);
            buffer.clear();
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            buffer.position(0);
            buffer.limit(bytes);
            if (!queue.offer(new RawFrame(width, height, buffer))) {
                bufferPool.offer(buffer);
            }
        } catch (Throwable t) {
            bufferPool.offer(buffer);
            McWebStreamClient.LOGGER.warn("Full-window capture failed", t);
        }
    }

    private ByteBuffer takeBuffer(int bytes) {
        ByteBuffer b;
        while ((b = bufferPool.poll()) != null) {
            if (b.capacity() == bytes) return b;
        }
        if (queue.remainingCapacity() == 0) return null;
        return ByteBuffer.allocateDirect(bytes);
    }

    private void encoderLoop() {
        while (running) {
            try {
                RawFrame first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                if (System.nanoTime() < nextEncoderRetryNs) {
                    bufferPool.offer(first.data);
                    continue;
                }

                EncoderChoice choice = chooseEncoder();
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
                McWebStreamClient.LOGGER.warn("AV1 encoder loop failed", t);
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
                if (frame == null) continue;
            }
        } finally {
            stopProcess();
        }
    }

    private void startEncoder(int inputWidth, int inputHeight, EncoderChoice choice) throws IOException {
        stopProcess();

        List<String> c = new ArrayList<>();
        c.add(config.ffmpeg);
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
                ":force_original_aspect_ratio=decrease:flags=bilinear,pad=" +
                config.videoWidth + ":" + config.videoHeight + ":(ow-iw)/2:(oh-ih)/2:black";
        c.add("-vf");
        c.add(filter);

        if (choice == EncoderChoice.AV1_NVENC) {
            c.add("-c:v");
            c.add("av1_nvenc");
            c.add("-preset");
            c.add("p4");
            c.add("-tune");
            c.add("ull");
            c.add("-rc");
            c.add("cbr");
            c.add("-b:v");
            c.add(config.videoBitrateKbps + "k");
            c.add("-maxrate");
            c.add(config.videoBitrateKbps + "k");
            c.add("-bufsize");
            c.add((config.videoBitrateKbps * 2) + "k");
            c.add("-g");
            c.add(Integer.toString(config.gop));
            c.add("-bf");
            c.add("0");
            c.add("-pix_fmt");
            c.add("yuv420p");
        } else {
            c.add("-c:v");
            c.add("libsvtav1");
            c.add("-preset");
            c.add("12");
            c.add("-b:v");
            c.add(config.videoBitrateKbps + "k");
            c.add("-maxrate");
            c.add(config.videoBitrateKbps + "k");
            c.add("-bufsize");
            c.add((config.videoBitrateKbps * 2) + "k");
            c.add("-g");
            c.add(Integer.toString(config.gop));
            c.add("-pix_fmt");
            c.add("yuv420p");
        }

        c.add("-f");
        c.add("ivf");
        c.add("pipe:1");

        McWebStreamClient.LOGGER.info("Starting AV1 stream encoder: {}", String.join(" ", c));
        Process started = new ProcessBuilder(c).start();
        process = started;

        Thread stderr = new Thread(() -> drainStderr(started.getErrorStream()), "mc-webstream-ffmpeg-log");
        stderr.setDaemon(true);
        stderr.start();

        Thread output = new Thread(() -> readIvf(started.getInputStream(), choice), "mc-webstream-video-out");
        output.setDaemon(true);
        output.start();

        server.sendVideoConfig("av01.0.05M.08", config.videoWidth, config.videoHeight,
                config.videoFps, config.videoBitrateKbps, inputWidth, inputHeight);
    }

    private void readIvf(InputStream raw, EncoderChoice choice) {
        try (BufferedInputStream in = new BufferedInputStream(raw, 1 << 20)) {
            byte[] header = readExactly(in, 32);
            if (header == null || header[0] != 'D' || header[1] != 'K' || header[2] != 'I' || header[3] != 'F') {
                throw new IOException("FFmpeg did not emit IVF");
            }

            long frameIndex = 0;
            while (running) {
                byte[] fh = readExactly(in, 12);
                if (fh == null) break;
                int size = le32(fh, 0);
                if (size <= 0 || size > 16 * 1024 * 1024) {
                    throw new IOException("Invalid IVF frame size: " + size);
                }
                byte[] payload = readExactly(in, size);
                if (payload == null) break;

                long ts = frameIndex * 1_000_000L / Math.max(1, config.videoFps);
                boolean key = frameIndex % Math.max(1, config.gop) == 0;
                server.sendVideoFrame(choice.codecId, key, ts, payload);
                frameIndex++;
            }
        } catch (IOException e) {
            if (running) McWebStreamClient.LOGGER.warn("AV1 output ended: {}", e.toString());
        }
    }

    private EncoderChoice chooseEncoder() {
        try {
            Process probe = new ProcessBuilder(config.ffmpeg, "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String out = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            if (!probe.waitFor(3, TimeUnit.SECONDS)) probe.destroyForcibly();

            if (out.contains("av1_nvenc")) return EncoderChoice.AV1_NVENC;
            if (out.contains("libsvtav1")) return EncoderChoice.SVT_AV1;

            McWebStreamClient.LOGGER.warn("FFmpeg has no AV1 encoder (need av1_nvenc or libsvtav1); video disabled");
        } catch (Exception e) {
            McWebStreamClient.LOGGER.warn("Could not execute FFmpeg '{}': {}", config.ffmpeg, e.toString());
        }
        return null;
    }

    private void drainStderr(InputStream stream) {
        try (stream) {
            byte[] bytes = stream.readAllBytes();
            if (bytes.length > 0) {
                String text = new String(bytes, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) McWebStreamClient.LOGGER.warn("FFmpeg: {}", text);
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

    private enum EncoderChoice {
        AV1_NVENC((byte) 1),
        SVT_AV1((byte) 1);

        final byte codecId;

        EncoderChoice(byte codecId) {
            this.codecId = codecId;
        }
    }
}
