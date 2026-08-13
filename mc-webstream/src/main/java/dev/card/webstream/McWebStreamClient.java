package dev.card.webstream;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

public final class McWebStreamClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mc-webstream");

    private static WebStreamServer server;
    private static BeautyEncoder beautyEncoder;
    private static Boolean lastScreenOpen;
    private static String lastScreenTitle;

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
            WebStreamServer runningServer = server;
            if (runningServer == null || !runningServer.hasClients()) {
                lastScreenOpen = null;
                lastScreenTitle = null;
                return;
            }

            boolean open = client.currentScreen != null;
            String title = open ? client.currentScreen.getTitle().getString() : "";
            if (!Objects.equals(lastScreenOpen, open) || !Objects.equals(lastScreenTitle, title)) {
                lastScreenOpen = open;
                lastScreenTitle = title;
                runningServer.sendUiState(open, title);
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());

        LOGGER.info("MC WebStream ready. Open http://<this-PC>:{} from a browser; WebSocket port is {}. " +
                        "Video defaults to {}x{} @ {} FPS, {} kbit/s AV1.",
                config.httpPort, config.wsPort, config.videoWidth, config.videoHeight,
                config.videoFps, config.videoBitrateKbps);
    }

    public static void captureFinalBackbuffer(int framebufferWidth, int framebufferHeight) {
        BeautyEncoder encoder = beautyEncoder;
        if (encoder != null) {
            encoder.captureBackbufferIfNeeded(framebufferWidth, framebufferHeight);
        }
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
