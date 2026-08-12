package dev.card.webstream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
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
        hello.addProperty("protocol", 1);
        hello.addProperty("cubeSize", SemanticStateTracker.SIZE);
        hello.addProperty("wsPort", config.wsPort);
        hello.addProperty("videoWidth", config.videoWidth);
        hello.addProperty("videoHeight", config.videoHeight);
        hello.addProperty("videoFps", config.videoFps);
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
            if (!obj.has("type") || !"input".equals(obj.get("type").getAsString())) return;
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> applyInput(client, obj));
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

    private static void applyInput(MinecraftClient client, JsonObject obj) {
        if (client.player == null) return;
        set(client.options.forwardKey, bool(obj, "forward"));
        set(client.options.backKey, bool(obj, "back"));
        set(client.options.leftKey, bool(obj, "left"));
        set(client.options.rightKey, bool(obj, "right"));
        set(client.options.jumpKey, bool(obj, "jump"));
        set(client.options.sneakKey, bool(obj, "sneak"));
        set(client.options.sprintKey, bool(obj, "sprint"));
        set(client.options.attackKey, bool(obj, "attack"));
        set(client.options.useKey, bool(obj, "use"));

        float dx = number(obj, "mouseDx"), dy = number(obj, "mouseDy");
        if (dx != 0 || dy != 0) {
            float yaw = client.player.getYaw() + dx * 0.12f;
            float pitch = Math.max(-90f, Math.min(90f, client.player.getPitch() + dy * 0.12f));
            client.player.setYaw(yaw);
            client.player.setPitch(pitch);
            client.player.setHeadYaw(yaw);
        }
    }

    private static boolean bool(JsonObject o, String key) { return o.has(key) && o.get(key).getAsBoolean(); }
    private static float number(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsFloat() : 0f; }
    private static void set(KeyBinding key, boolean down) { key.setPressed(down); }

    private static void releaseRemoteKeys() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            set(client.options.forwardKey, false); set(client.options.backKey, false);
            set(client.options.leftKey, false); set(client.options.rightKey, false);
            set(client.options.jumpKey, false); set(client.options.sneakKey, false);
            set(client.options.sprintKey, false); set(client.options.attackKey, false); set(client.options.useKey, false);
        });
    }
}
