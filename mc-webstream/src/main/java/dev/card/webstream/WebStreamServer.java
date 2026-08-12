package dev.card.webstream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
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
    private final StreamConfig config;
    private final Set<WebSocket> clients = ConcurrentHashMap.newKeySet();
    private final Set<InputUtil.Key> remotelyHeldKeys = ConcurrentHashMap.newKeySet();
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
        hello.addProperty("protocol", 2);
        hello.addProperty("cubeSize", SemanticStateTracker.SIZE);
        hello.addProperty("wsPort", config.wsPort);
        hello.addProperty("videoWidth", config.videoWidth);
        hello.addProperty("videoHeight", config.videoHeight);
        hello.addProperty("videoFps", config.videoFps);
        hello.addProperty("rawInput", true);
        hello.addProperty("remoteGui", true);
        conn.send(hello.toString());
        VideoConfig currentVideoConfig = lastVideoConfig;
        if (currentVideoConfig != null) conn.send(videoConfigJson(currentVideoConfig).toString());
        McWebStreamClient.requestFullSnapshot();
    }

    @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        if (clients.isEmpty()) releaseRemoteKeys();
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
            case "input" -> applyLook(client, obj);
            case "key" -> applyKeyboard(client, obj);
            case "mouse" -> applyMouse(client, obj);
            case "wheel" -> applyWheel(client, obj);
            case "char" -> applyChar(client, obj);
            case "release-all" -> releaseRemoteKeys();
            default -> { }
        }
    }

    private static void applyLook(MinecraftClient client, JsonObject obj) {
        if (client.player == null || client.currentScreen != null) return;
        float dx = number(obj, "mouseDx"), dy = number(obj, "mouseDy");
        if (dx == 0 && dy == 0) return;
        float yaw = client.player.getYaw() + dx * 0.12f;
        float pitch = Math.max(-90f, Math.min(90f, client.player.getPitch() + dy * 0.12f));
        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
        client.player.setHeadYaw(yaw);
    }

    private void applyKeyboard(MinecraftClient client, JsonObject obj) {
        int keyCode = integer(obj, "keyCode", -1);
        if (keyCode < 0) return;
        boolean down = bool(obj, "down");
        boolean repeat = bool(obj, "repeat");
        int modifiers = integer(obj, "modifiers", 0);

        if (client.currentScreen != null) {
            if (down) client.currentScreen.keyPressed(keyCode, 0, modifiers);
            else client.currentScreen.keyReleased(keyCode, 0, modifiers);
            return;
        }

        InputUtil.Key key = InputUtil.fromKeyCode(keyCode, 0);
        if (down) {
            remotelyHeldKeys.add(key);
            if (!repeat) KeyBinding.onKeyPressed(key);
        } else {
            remotelyHeldKeys.remove(key);
        }
        KeyBinding.setKeyPressed(key, down);
    }

    private void applyMouse(MinecraftClient client, JsonObject obj) {
        int button = integer(obj, "button", -1);
        if (button < 0) return;
        boolean down = bool(obj, "down");

        if (client.currentScreen != null) {
            double x = doubleNumber(obj, "x");
            double y = doubleNumber(obj, "y");
            if (down) client.currentScreen.mouseClicked(x, y, button);
            else client.currentScreen.mouseReleased(x, y, button);
            return;
        }

        InputUtil.Key key = InputUtil.fromTranslationKey(mouseTranslationKey(button));
        if (down) {
            remotelyHeldKeys.add(key);
            KeyBinding.onKeyPressed(key);
        } else {
            remotelyHeldKeys.remove(key);
        }
        KeyBinding.setKeyPressed(key, down);
    }

    private static void applyWheel(MinecraftClient client, JsonObject obj) {
        double amount = doubleNumber(obj, "amount");
        if (amount == 0) return;
        if (client.currentScreen != null) {
            client.currentScreen.mouseScrolled(doubleNumber(obj, "x"), doubleNumber(obj, "y"), amount);
        } else if (client.player != null) {
            client.player.getInventory().scrollInHotbar(amount);
        }
    }

    private static void applyChar(MinecraftClient client, JsonObject obj) {
        if (client.currentScreen == null || !obj.has("text")) return;
        String text = obj.get("text").getAsString();
        int modifiers = integer(obj, "modifiers", 0);
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            client.currentScreen.charTyped((char) codePoint, modifiers);
            offset += Character.charCount(codePoint);
        }
    }

    private static String mouseTranslationKey(int button) {
        return switch (button) {
            case 0 -> "key.mouse.left";
            case 1 -> "key.mouse.right";
            case 2 -> "key.mouse.middle";
            default -> "key.mouse." + (button + 1);
        };
    }

    private static boolean bool(JsonObject o, String key) { return o.has(key) && o.get(key).getAsBoolean(); }
    private static float number(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsFloat() : 0f; }
    private static double doubleNumber(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsDouble() : 0d; }
    private static int integer(JsonObject o, String key, int fallback) { return o.has(key) ? o.get(key).getAsInt() : fallback; }

    private void releaseRemoteKeys() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            for (InputUtil.Key key : remotelyHeldKeys) KeyBinding.setKeyPressed(key, false);
            remotelyHeldKeys.clear();
        });
    }
}
