package dev.card.webstream;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
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
import java.util.concurrent.atomic.AtomicLong;

final class BeautyEncoder implements AutoCloseable {
    private final StreamConfig config;
    private final WebStreamServer server;
    private final BlockingQueue<RawFrame> queue = new ArrayBlockingQueue<>(2);
    private final ConcurrentLinkedQueue<ByteBuffer> bufferPool = new ConcurrentLinkedQueue<>();
    private final AtomicLong encoderGeneration = new AtomicLong();
    private final Thread encoderThread;

    private volatile boolean running = true;
    private volatile Process process;
    private volatile int sourceWidth = -1;
    private volatile int sourceHeight = -1;
    private volatile long activeRevision = -1L;
    private volatile EncoderChoice selectedEncoder;
    private volatile Boolean useFlatpakHostFfmpeg;
    private volatile String availableEncoders;
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

    void captureBackbufferIfNeeded(int width, int height) {
        if (!running || !server.hasClients()) return;

        if (process != null && activeRevision != config.revision()) requestReconfigure();

        long now = System.nanoTime();
        long frameInterval = 1_000_000_000L / Math.max(1, config.videoFps);
        if (now - lastCaptureNs < frameInterval) return;
        lastCaptureNs = now;

        if (width <= 0 || height <= 0) return;

        if (width != sourceWidth || height != sourceHeight) {
            sourceWidth = width;
            sourceHeight = height;
            requestReconfigure();
            bufferPool.clear();
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

    void requestReconfigure() {
        EncoderChoice cached = selectedEncoder;
        if (cached != null && !cached.codec.equals(config.videoCodec)) selectedEncoder = null;
        nextEncoderRetryNs = 0L;
        drainQueue();
        stopProcess();
    }

    private void drainQueue() {
        RawFrame frame;
        while ((frame = queue.poll()) != null) {
            frame.data.clear();
            bufferPool.offer(frame.data);
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

                StreamConfig.StreamSettings settings = config.snapshot();
                choice = chooseEncoder(settings);
                if (choice == null) {
                    bufferPool.offer(first.data);
                    nextEncoderRetryNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    continue;
                }

                EncoderSession session = startEncoder(first.width, first.height, choice, settings);
                writeFrames(first, session);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                String codec = config.videoCodec.toUpperCase(Locale.ROOT);
                McWebStreamClient.LOGGER.warn("{} encoder loop failed{}", codec,
                        choice == null ? "" : " (" + choice + ")", t);
                if (choice != null && !choice.codec.equals(config.videoCodec)) selectedEncoder = null;
                stopProcess();
                nextEncoderRetryNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            }
        }
    }

    private void writeFrames(RawFrame first, EncoderSession session) throws IOException, InterruptedException {
        Process current = session.process;
        try (WritableByteChannel stdin = Channels.newChannel(current.getOutputStream())) {
            RawFrame frame = first;
            while (running && process == current && current.isAlive()
                    && config.revision() == session.settings.revision()) {
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
            stopProcess(current);
        }
    }

    private EncoderSession startEncoder(int inputWidth, int inputHeight, EncoderChoice choice,
                                        StreamConfig.StreamSettings settings) throws IOException {
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
        c.add(Integer.toString(settings.fps()));
        c.add("-i");
        c.add("pipe:0");
        c.add("-an");

        String filter = "vflip,scale=" + settings.width() + ":" + settings.height()
                + ":force_original_aspect_ratio=decrease:flags=fast_bilinear,pad="
                + settings.width() + ":" + settings.height() + ":(ow-iw)/2:(oh-ih)/2:black";
        c.add("-vf");
        c.add(filter);
        addEncoderArgs(c, choice, settings);
        c.add("-flush_packets");
        c.add("1");

        if (choice.codec.equals("av1")) {
            c.add("-f");
            c.add("ivf");
        } else {
            c.add("-f");
            c.add("hevc");
        }
        c.add("pipe:1");

        McWebStreamClient.LOGGER.info("Starting {} stream encoder [{}]: {}",
                choice.codec.toUpperCase(Locale.ROOT), choice, String.join(" ", c));
        Process started = new ProcessBuilder(c).start();
        process = started;
        activeRevision = settings.revision();
        long generation = encoderGeneration.incrementAndGet();

        Thread stderr = new Thread(() -> streamStderr(started.getErrorStream()), "mc-webstream-ffmpeg-log");
        stderr.setDaemon(true);
        stderr.start();

        Runnable outputTask = choice.codec.equals("av1")
                ? () -> readIvf(started, generation, choice, settings, inputWidth, inputHeight)
                : () -> readHevc(started, generation, choice, settings, inputWidth, inputHeight);
        Thread output = new Thread(outputTask, "mc-webstream-video-out");
        output.setDaemon(true);
        output.start();

        return new EncoderSession(started, generation, choice, settings);
    }

    private void addEncoderArgs(List<String> c, EncoderChoice choice, StreamConfig.StreamSettings settings) {
        int bitrate = settings.bitrateKbps();
        int gop = settings.gop();
        switch (choice) {
            case AV1_NVENC_LL, AV1_NVENC_HQ, AV1_NVENC_BASIC -> {
                c.add("-c:v"); c.add("av1_nvenc");
                c.add("-preset"); c.add("p4");
                if (choice == EncoderChoice.AV1_NVENC_LL) { c.add("-tune"); c.add("ull"); }
                if (choice == EncoderChoice.AV1_NVENC_HQ) { c.add("-tune"); c.add("hq"); }
                if (choice != EncoderChoice.AV1_NVENC_BASIC) { c.add("-rc"); c.add("cbr"); }
                addRateArgs(c, bitrate);
                c.add("-g"); c.add(Integer.toString(gop));
                c.add("-bf"); c.add("0");
                c.add("-pix_fmt"); c.add("nv12");
            }
            case SVT_AV1 -> {
                c.add("-c:v"); c.add("libsvtav1");
                c.add("-preset"); c.add("10");
                c.add("-b:v"); c.add(bitrate + "k");
                c.add("-g"); c.add(Integer.toString(gop));
                c.add("-pix_fmt"); c.add("yuv420p");
            }
            case AOM_AV1 -> {
                c.add("-c:v"); c.add("libaom-av1");
                c.add("-usage"); c.add("realtime");
                c.add("-cpu-used"); c.add("8");
                c.add("-row-mt"); c.add("1");
                c.add("-lag-in-frames"); c.add("0");
                c.add("-error-resilient"); c.add("1");
                c.add("-threads"); c.add(Integer.toString(Math.max(2,
                        Runtime.getRuntime().availableProcessors() / 2)));
                c.add("-b:v"); c.add(bitrate + "k");
                c.add("-g"); c.add(Integer.toString(gop));
                c.add("-pix_fmt"); c.add("yuv420p");
            }
            case HEVC_NVENC_LL, HEVC_NVENC_BASIC -> {
                c.add("-c:v"); c.add("hevc_nvenc");
                c.add("-preset"); c.add("p4");
                if (choice == EncoderChoice.HEVC_NVENC_LL) {
                    c.add("-tune"); c.add("ull");
                    c.add("-rc"); c.add("cbr");
                }
                addRateArgs(c, bitrate);
                c.add("-g"); c.add(Integer.toString(gop));
                c.add("-bf"); c.add("0");
                c.add("-aud"); c.add("1");
                c.add("-pix_fmt"); c.add("nv12");
            }
            case X265_HEVC -> {
                c.add("-c:v"); c.add("libx265");
                c.add("-preset"); c.add("ultrafast");
                c.add("-tune"); c.add("zerolatency");
                c.add("-b:v"); c.add(bitrate + "k");
                c.add("-maxrate"); c.add(bitrate + "k");
                c.add("-bufsize"); c.add((bitrate * 2) + "k");
                c.add("-x265-params");
                c.add("aud=1:repeat-headers=1:keyint=" + gop + ":min-keyint=" + gop
                        + ":scenecut=0:bframes=0:vbv-maxrate=" + bitrate
                        + ":vbv-bufsize=" + (bitrate * 2));
                c.add("-pix_fmt"); c.add("yuv420p");
            }
        }
    }

    private static void addRateArgs(List<String> c, int bitrate) {
        c.add("-b:v"); c.add(bitrate + "k");
        c.add("-maxrate"); c.add(bitrate + "k");
        c.add("-bufsize"); c.add((bitrate * 2) + "k");
    }

    private void readIvf(Process owner, long generation, EncoderChoice choice,
                         StreamConfig.StreamSettings settings, int inputWidth, int inputHeight) {
        try (BufferedInputStream in = new BufferedInputStream(owner.getInputStream(), 1 << 20)) {
            byte[] header = readExactly(in, 32);
            if (header == null || header[0] != 'D' || header[1] != 'K'
                    || header[2] != 'I' || header[3] != 'F') {
                throw new IOException("FFmpeg did not emit IVF");
            }

            if (!isCurrent(owner, generation, settings)) return;
            server.sendVideoConfig("av1", codecString(settings), settings.width(), settings.height(),
                    settings.fps(), settings.bitrateKbps(), inputWidth, inputHeight);

            long frameIndex = 0;
            while (running && isCurrent(owner, generation, settings)) {
                byte[] fh = readExactly(in, 12);
                if (fh == null) break;
                int size = le32(fh, 0);
                if (size <= 0 || size > 32 * 1024 * 1024) {
                    throw new IOException("Invalid IVF frame size: " + size);
                }
                byte[] payload = readExactly(in, size);
                if (payload == null) break;

                long ts = frameIndex * 1_000_000L / Math.max(1, settings.fps());
                boolean key = frameIndex % Math.max(1, settings.gop()) == 0;
                if (isCurrent(owner, generation, settings)) {
                    server.sendVideoFrame(choice.codecId, key, ts, payload);
                    logFirstFrame(payload.length, choice);
                }
                frameIndex++;
            }
        } catch (IOException e) {
            if (running && isCurrent(owner, generation, settings)) {
                McWebStreamClient.LOGGER.warn("AV1 output ended: {}", e.toString());
            }
        }
    }

    private void readHevc(Process owner, long generation, EncoderChoice choice,
                          StreamConfig.StreamSettings settings, int inputWidth, int inputHeight) {
        try (PushbackInputStream in = new PushbackInputStream(
                new BufferedInputStream(owner.getInputStream(), 1 << 20), 8)) {
            if (!isCurrent(owner, generation, settings)) return;
            server.sendVideoConfig("hevc", codecString(settings), settings.width(), settings.height(),
                    settings.fps(), settings.bitrateKbps(), inputWidth, inputHeight);

            ByteArrayOutputStream beforeFirstAud = new ByteArrayOutputStream();
            ByteArrayOutputStream current = null;
            ByteArrayOutputStream nextPrefix = new ByteArrayOutputStream();
            boolean currentHasVcl = false;
            long frameIndex = 0;

            byte[] nal;
            while (running && isCurrent(owner, generation, settings)
                    && (nal = readAnnexBNal(in)) != null) {
                int type = hevcNalType(nal);
                if (type < 0) continue;

                if (type == 35) {
                    if (current != null && currentHasVcl) {
                        emitHevcAccessUnit(owner, generation, choice, settings,
                                frameIndex++, current.toByteArray());
                    }
                    current = new ByteArrayOutputStream();
                    if (beforeFirstAud.size() > 0) {
                        current.writeBytes(beforeFirstAud.toByteArray());
                        beforeFirstAud.reset();
                    }
                    if (nextPrefix.size() > 0) {
                        current.writeBytes(nextPrefix.toByteArray());
                        nextPrefix.reset();
                    }
                    current.writeBytes(nal);
                    currentHasVcl = false;
                    continue;
                }

                if (current == null) {
                    beforeFirstAud.writeBytes(nal);
                    continue;
                }

                boolean prefixForNext = currentHasVcl && (type == 32 || type == 33 || type == 34 || type == 39);
                if (prefixForNext) {
                    nextPrefix.writeBytes(nal);
                } else {
                    current.writeBytes(nal);
                    if (type >= 0 && type <= 31) currentHasVcl = true;
                }
            }

            if (current != null && currentHasVcl && isCurrent(owner, generation, settings)) {
                emitHevcAccessUnit(owner, generation, choice, settings,
                        frameIndex, current.toByteArray());
            }
        } catch (IOException e) {
            if (running && isCurrent(owner, generation, settings)) {
                McWebStreamClient.LOGGER.warn("HEVC output ended: {}", e.toString());
            }
        }
    }

    private void emitHevcAccessUnit(Process owner, long generation, EncoderChoice choice,
                                    StreamConfig.StreamSettings settings, long frameIndex, byte[] payload) {
        if (payload.length == 0 || !isCurrent(owner, generation, settings)) return;
        long ts = frameIndex * 1_000_000L / Math.max(1, settings.fps());
        boolean key = containsHevcRandomAccess(payload);
        server.sendVideoFrame(choice.codecId, key, ts, payload);
        logFirstFrame(payload.length, choice);
    }

    private void logFirstFrame(int length, EncoderChoice choice) {
        if (!loggedFirstEncoded) {
            loggedFirstEncoded = true;
            McWebStreamClient.LOGGER.info("WebStream emitted first {} frame ({} bytes, encoder={})",
                    choice.codec.toUpperCase(Locale.ROOT), length, choice);
        }
    }

    private boolean isCurrent(Process owner, long generation, StreamConfig.StreamSettings settings) {
        return process == owner && encoderGeneration.get() == generation
                && config.revision() == settings.revision();
    }

    private String codecString(StreamConfig.StreamSettings settings) {
        if (settings.codec().equals("hevc")) {
            long pixels = (long) settings.width() * settings.height();
            if (pixels > 3840L * 2160L || settings.fps() > 60) return "hev1.1.6.L186.B0";
            if (pixels > 1920L * 1080L || settings.fps() > 30) return "hev1.1.6.L153.B0";
            if (pixels > 1280L * 720L || settings.fps() > 30) return "hev1.1.6.L123.B0";
            return "hev1.1.6.L93.B0";
        }
        if (settings.width() > 1920 || settings.height() > 1080 || settings.fps() > 120) {
            return "av01.0.12M.08";
        }
        if (settings.width() > 1280 || settings.height() > 720 || settings.fps() > 30) {
            return "av01.0.08M.08";
        }
        return "av01.0.05M.08";
    }

    private EncoderChoice chooseEncoder(StreamConfig.StreamSettings settings) {
        EncoderChoice cached = selectedEncoder;
        if (cached != null && cached.codec.equals(settings.codec())) return cached;

        String encoders = availableEncoderText();
        if (encoders == null) return null;

        EncoderChoice[] candidates = settings.codec().equals("hevc")
                ? new EncoderChoice[]{EncoderChoice.HEVC_NVENC_LL,
                EncoderChoice.HEVC_NVENC_BASIC, EncoderChoice.X265_HEVC}
                : new EncoderChoice[]{EncoderChoice.AV1_NVENC_LL,
                EncoderChoice.AV1_NVENC_HQ, EncoderChoice.AV1_NVENC_BASIC,
                EncoderChoice.SVT_AV1, EncoderChoice.AOM_AV1};

        for (EncoderChoice candidate : candidates) {
            if (!encoders.contains(candidate.ffmpegName)) continue;
            ProbeResult result = probeEncoder(candidate, settings);
            if (result.ok) {
                selectedEncoder = candidate;
                McWebStreamClient.LOGGER.info("{} encoder probe selected {}",
                        settings.codec().toUpperCase(Locale.ROOT), candidate);
                return candidate;
            }
            McWebStreamClient.LOGGER.warn("{} encoder probe rejected {}: {}",
                    settings.codec().toUpperCase(Locale.ROOT), candidate, result.message);
        }

        McWebStreamClient.LOGGER.warn("FFmpeg has no usable {} encoder",
                settings.codec().toUpperCase(Locale.ROOT));
        return null;
    }

    private String availableEncoderText() {
        String cached = availableEncoders;
        if (cached != null) return cached;
        try {
            List<String> c = ffmpegCommand();
            c.add("-hide_banner");
            c.add("-encoders");
            Process probe = new ProcessBuilder(c).redirectErrorStream(true).start();
            String text = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            if (!probe.waitFor(3, TimeUnit.SECONDS)) probe.destroyForcibly();
            availableEncoders = text;
            return text;
        } catch (Exception e) {
            McWebStreamClient.LOGGER.warn("Could not execute FFmpeg '{}': {}", config.ffmpeg, e.toString());
            return null;
        }
    }

    private ProbeResult probeEncoder(EncoderChoice choice, StreamConfig.StreamSettings settings) {
        List<String> c = ffmpegCommand();
        c.add("-hide_banner");
        c.add("-loglevel");
        c.add("error");
        c.add("-f");
        c.add("lavfi");
        c.add("-i");
        c.add("color=c=black:s=" + settings.width() + "x" + settings.height()
                + ":r=" + settings.fps());
        c.add("-frames:v");
        c.add("1");
        addEncoderArgs(c, choice, settings);
        c.add("-f");
        c.add("null");
        c.add("-");

        try {
            Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
            boolean exited = p.waitFor(8, TimeUnit.SECONDS);
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
                    McWebStreamClient.LOGGER.info("Flatpak detected; using host FFmpeg via flatpak-spawn: {}",
                            firstLine);
                    return true;
                }
                useFlatpakHostFfmpeg = false;
                McWebStreamClient.LOGGER.warn("Flatpak host FFmpeg unavailable; using sandbox FFmpeg: {}",
                        output.replace('\n', ' ').replace('\r', ' '));
                return false;
            } catch (Exception e) {
                useFlatpakHostFfmpeg = false;
                McWebStreamClient.LOGGER.warn(
                        "Could not invoke host FFmpeg through flatpak-spawn; using sandbox FFmpeg: {}",
                        e.toString());
                return false;
            }
        }
    }

    private String hostFfmpegPath() {
        if (config.ffmpeg == null || config.ffmpeg.isBlank() || "ffmpeg".equals(config.ffmpeg)) {
            return "/usr/bin/ffmpeg";
        }
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

    private static byte[] readAnnexBNal(PushbackInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        int zeros = 0;
        boolean found = false;
        int b;

        while ((b = in.read()) >= 0) {
            if (b == 0) {
                zeros++;
                continue;
            }
            if (b == 1 && zeros >= 2) {
                if (zeros >= 3) out.writeBytes(new byte[]{0, 0, 0, 1});
                else out.writeBytes(new byte[]{0, 0, 1});
                found = true;
                break;
            }
            zeros = 0;
        }
        if (!found) return null;

        zeros = 0;
        while ((b = in.read()) >= 0) {
            if (b == 0) {
                zeros++;
                continue;
            }
            if (b == 1 && zeros >= 2) {
                byte[] startCode = zeros >= 3
                        ? new byte[]{0, 0, 0, 1}
                        : new byte[]{0, 0, 1};
                in.unread(startCode);
                return out.toByteArray();
            }
            while (zeros-- > 0) out.write(0);
            zeros = 0;
            out.write(b);
        }
        while (zeros-- > 0) out.write(0);
        return out.size() > 4 ? out.toByteArray() : null;
    }

    private static int hevcNalType(byte[] nal) {
        int offset;
        if (nal.length >= 5 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) offset = 4;
        else if (nal.length >= 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 1) offset = 3;
        else return -1;
        return (nal[offset] >> 1) & 0x3f;
    }

    private static boolean containsHevcRandomAccess(byte[] accessUnit) {
        int i = 0;
        while (i + 5 < accessUnit.length) {
            int start = -1;
            if (accessUnit[i] == 0 && accessUnit[i + 1] == 0 && accessUnit[i + 2] == 1) start = i + 3;
            else if (i + 4 < accessUnit.length && accessUnit[i] == 0 && accessUnit[i + 1] == 0
                    && accessUnit[i + 2] == 0 && accessUnit[i + 3] == 1) start = i + 4;
            if (start >= 0 && start < accessUnit.length) {
                int type = (accessUnit[start] >> 1) & 0x3f;
                if (type >= 16 && type <= 21) return true;
                i = start + 2;
            } else {
                i++;
            }
        }
        return false;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8)
                | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }

    private synchronized void stopProcess() {
        stopProcess(process);
    }

    private synchronized void stopProcess(Process expected) {
        Process p = process;
        if (p == null || (expected != null && p != expected)) return;
        process = null;
        activeRevision = -1L;
        encoderGeneration.incrementAndGet();
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
        drainQueue();
        stopProcess();
    }

    private record RawFrame(int width, int height, ByteBuffer data) {
    }

    private record ProbeResult(boolean ok, String message) {
    }

    private record EncoderSession(Process process, long generation, EncoderChoice choice,
                                  StreamConfig.StreamSettings settings) {
    }

    private enum EncoderChoice {
        AV1_NVENC_LL("av1", "av1_nvenc", (byte) 1),
        AV1_NVENC_HQ("av1", "av1_nvenc", (byte) 1),
        AV1_NVENC_BASIC("av1", "av1_nvenc", (byte) 1),
        SVT_AV1("av1", "libsvtav1", (byte) 1),
        AOM_AV1("av1", "libaom-av1", (byte) 1),
        HEVC_NVENC_LL("hevc", "hevc_nvenc", (byte) 2),
        HEVC_NVENC_BASIC("hevc", "hevc_nvenc", (byte) 2),
        X265_HEVC("hevc", "libx265", (byte) 2);

        final String codec;
        final String ffmpegName;
        final byte codecId;

        EncoderChoice(String codec, String ffmpegName, byte codecId) {
            this.codec = codec;
            this.ffmpegName = ffmpegName;
            this.codecId = codecId;
        }
    }
}
