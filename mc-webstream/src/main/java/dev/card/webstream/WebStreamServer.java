package dev.card.webstream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.card.webstream.mixin.KeyboardInvoker;
import dev.card.webstream.mixin.MouseInvoker;
import net.minecraft.client.MinecraftClient;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

final class WebStreamServer extends WebSocketServer {
    private static final int GLFW_RELEASE = 0;
    private static final int GLFW_PRESS = 1;

    private final StreamConfig config;
    private final Set<WebSocket> clients = ConcurrentHashMap.newKeySet();
    private final Set<Integer> remotelyHeldKeys = ConcurrentHashMap.newKeySet();
    private final Set<Integer> remotelyHeldMouseButtons = ConcurrentHashMap.newKeySet();

    private HttpServer httpServer;
    private volatile BeautyEncoder encoder;
    private volatile VideoConfig lastVideoConfig;
    private volatile int browserWidth = 1280;
    private volatile int browserHeight = 720;

    WebStreamServer(StreamConfig config) {
        super(new InetSocketAddress(config.bindHost, config.wsPort));
        this.config = config;
        setReuseAddr(true);
        setTcpNoDelay(true);
        setConnectionLostTimeout(5);
    }

    void attachEncoder(BeautyEncoder encoder) {
        this.encoder = encoder;
    }

    void startAll() throws IOException {
        start();

        httpServer = HttpServer.create(new InetSocketAddress(config.bindHost, config.httpPort), 0);
        httpServer.createContext("/", this::serveViewer);
        httpServer.createContext("/config", exchange -> {
            byte[] bytes = settingsJson("config").toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        httpServer.createContext("/health", exchange -> {
            byte[] bytes = "ok\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mc-webstream-http");
            t.setDaemon(true);
            return t;
        }));
        httpServer.start();
    }

    boolean hasClients() {
        return !clients.isEmpty();
    }

    void sendUiState(boolean screenOpen, String title) {
        if (clients.isEmpty()) return;
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "ui-state");
        obj.addProperty("screenOpen", screenOpen);
        obj.addProperty("title", title == null ? "" : title);
        broadcast(obj.toString());
    }

    void sendVideoConfig(String codecName, String codec, int width, int height, int fps, int bitrateKbps,
                         int sourceWidth, int sourceHeight) {
        lastVideoConfig = new VideoConfig(codecName, codec, width, height, fps,
                bitrateKbps, sourceWidth, sourceHeight);
        if (!clients.isEmpty()) broadcast(videoConfigJson(lastVideoConfig).toString());
    }

    void sendVideoFrame(byte codecId, boolean keyFrame, long timestampUs, byte[] payload) {
        if (clients.isEmpty()) return;
        ByteBuffer packet = ByteBuffer.allocate(12 + payload.length);
        packet.put((byte) 0x56);
        packet.put(codecId);
        packet.put((byte) (keyFrame ? 1 : 0));
        packet.put((byte) 0);
        packet.putLong(timestampUs);
        packet.put(payload);
        broadcast(packet.array());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);

        JsonObject hello = settingsJson("hello");
        hello.addProperty("protocol", 5);
        hello.addProperty("nativeInputInjection", true);
        conn.send(hello.toString());

        VideoConfig currentVideoConfig = lastVideoConfig;
        if (currentVideoConfig != null) conn.send(videoConfigJson(currentVideoConfig).toString());

        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            boolean open = client.currentScreen != null;
            JsonObject ui = new JsonObject();
            ui.addProperty("type", "ui-state");
            ui.addProperty("screenOpen", open);
            ui.addProperty("title", open ? client.currentScreen.getTitle().getString() : "");
            conn.send(ui.toString());
        });
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        if (clients.isEmpty()) releaseRemoteInput();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
            if (!obj.has("type")) return;
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> applyMessage(client, conn, obj));
        } catch (Exception e) {
            McWebStreamClient.LOGGER.debug("Ignoring malformed web input: {}", e.toString());
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        McWebStreamClient.LOGGER.warn("WebStream socket error", ex);
    }

    @Override
    public void onStart() {
        McWebStreamClient.LOGGER.info("MC WebStream WebSocket listening on {}:{}",
                config.bindHost, config.wsPort);
    }

    void stopAll() throws InterruptedException {
        HttpServer http = httpServer;
        httpServer = null;
        if (http != null) http.stop(0);
        releaseRemoteInput();
        stop(500);
    }

    private JsonObject settingsJson(String type) {
        StreamConfig.StreamSettings settings = config.snapshot();
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("wsPort", config.wsPort);
        obj.addProperty("codecName", settings.codec());
        obj.addProperty("videoWidth", settings.width());
        obj.addProperty("videoHeight", settings.height());
        obj.addProperty("videoFps", settings.fps());
        obj.addProperty("videoBitrateKbps", settings.bitrateKbps());
        obj.addProperty("resolutionCap", settings.resolutionCap());
        obj.addProperty("maxPixels", settings.maxPixels());
        obj.addProperty("browserWidth", browserWidth);
        obj.addProperty("browserHeight", browserHeight);
        obj.addProperty("settingsRevision", settings.revision());
        return obj;
    }

    private void sendSettingsState(WebSocket only) {
        JsonObject state = settingsJson("stream-settings-state");
        if (only == null) broadcast(state.toString());
        else only.send(state.toString());
    }

    private static JsonObject videoConfigJson(VideoConfig config) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "video-config");
        obj.addProperty("codecName", config.codecName);
        obj.addProperty("codec", config.codec);
        obj.addProperty("width", config.width);
        obj.addProperty("height", config.height);
        obj.addProperty("fps", config.fps);
        obj.addProperty("bitrateKbps", config.bitrateKbps);
        obj.addProperty("sourceWidth", config.sourceWidth);
        obj.addProperty("sourceHeight", config.sourceHeight);
        return obj;
    }

    private record VideoConfig(String codecName, String codec, int width, int height, int fps,
                               int bitrateKbps, int sourceWidth, int sourceHeight) {
    }

    private void serveViewer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/favicon.ico")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!path.equals("/") && !path.equals("/index.html")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        try (InputStream in = WebStreamServer.class.getResourceAsStream(
                "/assets/mcwebstream/viewer/index.html")) {
            if (in == null) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private void applyMessage(MinecraftClient client, WebSocket conn, JsonObject obj) {
        switch (obj.get("type").getAsString()) {
            case "viewport" -> applyViewport(client, conn, obj);
            case "stream-settings" -> applyStreamSettings(client, conn, obj);
            case "request-settings" -> sendSettingsState(conn);
            case "key" -> applyKeyboard(client, obj);
            case "char" -> applyChar(client, obj);
            case "mouse-move" -> applyMouseMove(client, obj);
            case "mouse" -> applyMouseButton(client, obj);
            case "wheel" -> applyWheel(client, obj);
            case "release-all" -> releaseRemoteInput();
            default -> {
            }
        }
    }

    private void applyViewport(MinecraftClient client, WebSocket conn, JsonObject obj) {
        int requestedWidth = integer(obj, "width", 0);
        int requestedHeight = integer(obj, "height", 0);
        if (!validViewport(requestedWidth, requestedHeight)) {
            sendSettingsState(conn);
            return;
        }

        browserWidth = Math.min(requestedWidth, 7680);
        browserHeight = Math.min(requestedHeight, 4320);
        long before = config.revision();
        resizeForBrowser(client);
        finishReconfigure(before);
    }

    private void applyStreamSettings(MinecraftClient client, WebSocket conn, JsonObject obj) {
        String codec = obj.has("codec") ? obj.get("codec").getAsString() : config.videoCodec;
        int fps = integer(obj, "fps", config.videoFps);
        int bitrate = integer(obj, "bitrateKbps", config.videoBitrateKbps);
        int cap = integer(obj, "resolutionCap", config.resolutionCap);

        int requestedWidth = integer(obj, "viewportWidth", browserWidth);
        int requestedHeight = integer(obj, "viewportHeight", browserHeight);
        if (validViewport(requestedWidth, requestedHeight)) {
            browserWidth = Math.min(requestedWidth, 7680);
            browserHeight = Math.min(requestedHeight, 4320);
        }

        long before = config.revision();
        StreamConfig.StreamSettings accepted = config.applySettings(codec, fps, bitrate, cap);
        resizeForBrowser(client);
        finishReconfigure(before);

        McWebStreamClient.LOGGER.info(
                "Browser applied stream settings: codec={}, cap={}p, {} FPS, {} kbit/s, output={}x{}",
                accepted.codec().toUpperCase(), accepted.resolutionCap(), accepted.fps(),
                accepted.bitrateKbps(), config.videoWidth, config.videoHeight);
        sendSettingsState(conn);
    }

    private void resizeForBrowser(MinecraftClient client) {
        int requestedWidth = browserWidth;
        int requestedHeight = browserHeight;
        if (!validViewport(requestedWidth, requestedHeight)) return;

        long maxPixels = StreamConfig.maxPixelsForCap(config.resolutionCap);
        double pixels = (double) requestedWidth * requestedHeight;
        double scale = pixels > maxPixels ? Math.sqrt(maxPixels / pixels) : 1.0;
        int width = Math.max(64, ((int) Math.floor(requestedWidth * scale)) & ~1);
        int height = Math.max(64, ((int) Math.floor(requestedHeight * scale)) & ~1);

        config.setOutputSize(width, height);
        if (Math.abs(client.getWindow().getWidth() - width) > 2
                || Math.abs(client.getWindow().getHeight() - height) > 2) {
            GLFW.glfwSetWindowSize(client.getWindow().getHandle(), width, height);
        }
    }

    private void finishReconfigure(long revisionBefore) {
        if (config.revision() != revisionBefore) {
            lastVideoConfig = null;
            BeautyEncoder currentEncoder = encoder;
            if (currentEncoder != null) currentEncoder.requestReconfigure();
            sendSettingsState(null);
        }
    }

    private static boolean validViewport(int width, int height) {
        return width >= 64 && height >= 64 && width >= height;
    }

    private void applyKeyboard(MinecraftClient client, JsonObject obj) {
        int keyCode = integer(obj, "keyCode", -1);
        if (keyCode < 0) return;
        int action = integer(obj, "action", GLFW_PRESS);
        int modifiers = integer(obj, "modifiers", 0);
        long handle = client.getWindow().getHandle();

        if (action == GLFW_RELEASE) remotelyHeldKeys.remove(keyCode);
        else if (action == GLFW_PRESS) remotelyHeldKeys.add(keyCode);

        keyboard(client).mcwebstream$invokeOnKey(handle, keyCode, 0, action, modifiers);
    }

    private static void applyChar(MinecraftClient client, JsonObject obj) {
        if (!obj.has("text")) return;
        int modifiers = integer(obj, "modifiers", 0);
        long handle = client.getWindow().getHandle();
        String text = obj.get("text").getAsString();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            keyboard(client).mcwebstream$invokeOnChar(handle, codePoint, modifiers);
            offset += Character.charCount(codePoint);
        }
    }

    private static void applyMouseMove(MinecraftClient client, JsonObject obj) {
        long handle = client.getWindow().getHandle();
        MouseInvoker mouse = mouse(client);
        if (bool(obj, "relative")) {
            double dx = doubleNumber(obj, "dx");
            double dy = doubleNumber(obj, "dy");
            mouse.mcwebstream$invokeOnCursorPos(handle, client.mouse.getX() + dx, client.mouse.getY() + dy);
        } else {
            double xNorm = clamp01(doubleNumber(obj, "xNorm"));
            double yNorm = clamp01(doubleNumber(obj, "yNorm"));
            mouse.mcwebstream$invokeOnCursorPos(handle,
                    xNorm * client.getWindow().getWidth(),
                    yNorm * client.getWindow().getHeight());
        }
    }

    private void applyMouseButton(MinecraftClient client, JsonObject obj) {
        int button = integer(obj, "button", -1);
        if (button < 0) return;
        int action = integer(obj, "action", GLFW_PRESS);
        int modifiers = integer(obj, "modifiers", 0);
        long handle = client.getWindow().getHandle();
        MouseInvoker mouse = mouse(client);

        if (obj.has("xNorm") && obj.has("yNorm")) {
            mouse.mcwebstream$invokeOnCursorPos(handle,
                    clamp01(doubleNumber(obj, "xNorm")) * client.getWindow().getWidth(),
                    clamp01(doubleNumber(obj, "yNorm")) * client.getWindow().getHeight());
        }

        if (action == GLFW_RELEASE) remotelyHeldMouseButtons.remove(button);
        else if (action == GLFW_PRESS) remotelyHeldMouseButtons.add(button);

        mouse.mcwebstream$invokeOnMouseButton(handle, button, action, modifiers);
    }

    private static void applyWheel(MinecraftClient client, JsonObject obj) {
        double amount = doubleNumber(obj, "amount");
        if (amount == 0) return;
        long handle = client.getWindow().getHandle();
        MouseInvoker mouse = mouse(client);
        if (obj.has("xNorm") && obj.has("yNorm")) {
            mouse.mcwebstream$invokeOnCursorPos(handle,
                    clamp01(doubleNumber(obj, "xNorm")) * client.getWindow().getWidth(),
                    clamp01(doubleNumber(obj, "yNorm")) * client.getWindow().getHeight());
        }
        mouse.mcwebstream$invokeOnMouseScroll(handle, 0, amount);
    }

    private void releaseRemoteInput() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            long handle = client.getWindow().getHandle();
            KeyboardInvoker keyboard = keyboard(client);
            MouseInvoker mouse = mouse(client);
            for (Integer key : remotelyHeldKeys) {
                keyboard.mcwebstream$invokeOnKey(handle, key, 0, GLFW_RELEASE, 0);
            }
            for (Integer button : remotelyHeldMouseButtons) {
                mouse.mcwebstream$invokeOnMouseButton(handle, button, GLFW_RELEASE, 0);
            }
            remotelyHeldKeys.clear();
            remotelyHeldMouseButtons.clear();
        });
    }

    private static KeyboardInvoker keyboard(MinecraftClient client) {
        return (KeyboardInvoker) (Object) client.keyboard;
    }

    private static MouseInvoker mouse(MinecraftClient client) {
        return (MouseInvoker) (Object) client.mouse;
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }

    private static double doubleNumber(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsDouble() : 0d;
    }

    private static int integer(JsonObject o, String key, int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }

    private static double clamp01(double v) {
        return Math.max(0d, Math.min(1d, v));
    }
}
