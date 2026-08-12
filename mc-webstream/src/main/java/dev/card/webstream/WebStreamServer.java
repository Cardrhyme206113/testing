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
    private volatile VideoConfig lastVideoConfig;

    WebStreamServer(StreamConfig config) {
        super(new InetSocketAddress(config.bindHost, config.wsPort));
        this.config = config;
        setReuseAddr(true);
        setTcpNoDelay(true);
        setConnectionLostTimeout(5);
    }

    void startAll() throws IOException {
        start();
        httpServer = HttpServer.create(new InetSocketAddress(config.bindHost, config.httpPort), 0);
        httpServer.createContext("/", this::serveViewer);
        httpServer.createContext("/config", exchange -> {
            JsonObject cfg = new JsonObject();
            cfg.addProperty("wsPort", config.wsPort);
            cfg.addProperty("videoWidth", config.videoWidth);
            cfg.addProperty("videoHeight", config.videoHeight);
            cfg.addProperty("videoFps", config.videoFps);
            byte[] bytes = cfg.toString().getBytes(StandardCharsets.UTF_8);
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

    boolean hasClients() { return !clients.isEmpty(); }

    void sendState(String json) {
        if (!clients.isEmpty()) broadcast(json);
    }

    void sendVideoConfig(String codec, int width, int height, int fps) {
        lastVideoConfig = new VideoConfig(codec, width, height, fps);
        broadcast(videoConfigJson(lastVideoConfig).toString());
    }

    void sendVideoFrame(byte codecId, boolean keyFrame, long timestampUs,
                        float x, float y, float z, float yaw, float pitch, byte[] payload) {
        if (clients.isEmpty()) return;
        ByteBuffer packet = ByteBuffer.allocate(32 + payload.length);
        packet.put((byte) 0x56);
        packet.put(codecId);
        packet.put((byte) (keyFrame ? 1 : 0));
        packet.put((byte) 0);
        packet.putLong(timestampUs);
        packet.putFloat(x); packet.putFloat(y); packet.putFloat(z);
        packet.putFloat(yaw); packet.putFloat(pitch);
        packet.put(payload);
        broadcast(packet.array());
    }

    @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocol", 3);
        hello.addProperty("cubeSize", SemanticStateTracker.SIZE);
        hello.addProperty("wsPort", config.wsPort);
        hello.addProperty("videoWidth", config.videoWidth);
        hello.addProperty("videoHeight", config.videoHeight);
        hello.addProperty("videoFps", config.videoFps);
        hello.addProperty("nativeInputInjection", true);
        hello.addProperty("remoteGui", true);
        conn.send(hello.toString());
        VideoConfig currentVideoConfig = lastVideoConfig;
        if (currentVideoConfig != null) conn.send(videoConfigJson(currentVideoConfig).toString());
        McWebStreamClient.requestFullSnapshot();
    }

    @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        if (clients.isEmpty()) releaseRemoteInput();
    }

    @Override public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
            if (!obj.has("type")) return;
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> applyMessage(client, obj));
        } catch (Exception e) {
            McWebStreamClient.LOGGER.debug("Ignoring malformed web input: {}", e.toString());
        }
    }

    @Override public void onMessage(WebSocket conn, ByteBuffer message) {}
    @Override public void onError(WebSocket conn, Exception ex) { McWebStreamClient.LOGGER.warn("WebStream socket error", ex); }
    @Override public void onStart() { McWebStreamClient.LOGGER.info("MC WebStream WebSocket listening on {}:{}", config.bindHost, config.wsPort); }

    void stopAll() throws InterruptedException {
        HttpServer http = httpServer;
        httpServer = null;
        if (http != null) http.stop(0);
        releaseRemoteInput();
        stop(500);
    }

    private static JsonObject videoConfigJson(VideoConfig config) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "video-config");
        obj.addProperty("codec", config.codec);
        obj.addProperty("width", config.width);
        obj.addProperty("height", config.height);
        obj.addProperty("fps", config.fps);
        return obj;
    }

    private record VideoConfig(String codec, int width, int height, int fps) {}

    private void serveViewer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        try (InputStream in = WebStreamServer.class.getResourceAsStream("/assets/mcwebstream/viewer/index.html")) {
            if (in == null) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private void applyMessage(MinecraftClient client, JsonObject obj) {
        switch (obj.get("type").getAsString()) {
            case "key" -> applyKeyboard(client, obj);
            case "char" -> applyChar(client, obj);
            case "mouse-move" -> applyMouseMove(client, obj);
            case "mouse" -> applyMouseButton(client, obj);
            case "wheel" -> applyWheel(client, obj);
            case "release-all" -> releaseRemoteInput();
            default -> { }
        }
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
        for (int offset = 0; offset < text.length();) {
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
            for (Integer key : remotelyHeldKeys) keyboard.mcwebstream$invokeOnKey(handle, key, 0, GLFW_RELEASE, 0);
            for (Integer button : remotelyHeldMouseButtons) mouse.mcwebstream$invokeOnMouseButton(handle, button, GLFW_RELEASE, 0);
            remotelyHeldKeys.clear();
            remotelyHeldMouseButtons.clear();
        });
    }

    private static KeyboardInvoker keyboard(MinecraftClient client) { return (KeyboardInvoker) (Object) client.keyboard; }
    private static MouseInvoker mouse(MinecraftClient client) { return (MouseInvoker) (Object) client.mouse; }
    private static boolean bool(JsonObject o, String key) { return o.has(key) && o.get(key).getAsBoolean(); }
    private static double doubleNumber(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsDouble() : 0d; }
    private static int integer(JsonObject o, String key, int fallback) { return o.has(key) ? o.get(key).getAsInt() : fallback; }
    private static double clamp01(double v) { return Math.max(0d, Math.min(1d, v)); }
}
