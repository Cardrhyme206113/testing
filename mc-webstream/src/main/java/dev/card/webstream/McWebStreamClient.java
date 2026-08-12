package dev.card.webstream;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McWebStreamClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mc-webstream");

    private static final SemanticStateTracker TRACKER = new SemanticStateTracker();
    private static final AtomicBoolean FULL_SNAPSHOT_REQUESTED = new AtomicBoolean(true);

    private static WebStreamServer server;
    private static BeautyEncoder beautyEncoder;

    @Override
    public void onInitializeClient() {
        StreamConfig config = StreamConfig.fromEnvironment();
        server = new WebStreamServer(config);
        try {
            server.startAll();
        } catch (IOException e) {
            LOGGER.error("Could not start MC WebStream servers", e);
            return;
        }

        beautyEncoder = new BeautyEncoder(config, server);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (FULL_SNAPSHOT_REQUESTED.getAndSet(false)) {
                TRACKER.reset();
            }
            if (server == null || !server.hasClients()) {
                return;
            }
            var state = TRACKER.sample(client);
            if (state != null) {
                server.sendState(state.toString());
            }
        });

        WorldRenderEvents.END.register(context -> {
            BeautyEncoder encoder = beautyEncoder;
            if (encoder != null) {
                encoder.captureIfNeeded(MinecraftClient.getInstance());
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());

        LOGGER.info("MC WebStream ready. Open http://<this-PC>:{} from a browser; WebSocket port is {}.",
                config.httpPort, config.wsPort);
    }

    static void requestFullSnapshot() {
        FULL_SNAPSHOT_REQUESTED.set(true);
    }

    private static void shutdown() {
        BeautyEncoder encoder = beautyEncoder;
        beautyEncoder = null;
        if (encoder != null) encoder.close();

        WebStreamServer runningServer = server;
        server = null;
        if (runningServer != null) {
            try {
                runningServer.stopAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
