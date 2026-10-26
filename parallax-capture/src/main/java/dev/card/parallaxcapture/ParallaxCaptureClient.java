package dev.card.parallaxcapture;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ParallaxCaptureClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ParallaxCapture");
    private static final KeyBinding CAPTURE_KEY = new KeyBinding(
            "key.parallaxcapture.capture",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.parallaxcapture"
    );

    private static CaptureSession session;
    private static CaptureConfig config;

    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(CAPTURE_KEY);
        config = CaptureConfig.load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (CAPTURE_KEY.wasPressed()) {
                if (session != null && !session.isDone()) {
                    session.cancel();
                } else {
                    start(client);
                }
            }
        });
    }

    public static CaptureConfig getConfig() {
        if (config == null) config = CaptureConfig.load();
        return config;
    }

    public static void applyConfig(CaptureConfig newConfig) {
        newConfig.clamp();
        config = newConfig.copy();
        config.save();
    }

    private static void start(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        try {
            CaptureConfig cfg = getConfig().copy();
            session = new CaptureSession(client, cfg);
            client.player.sendMessage(Text.literal("Parallax capture started. F8 cancels. Don't touch the camera until it finishes.").formatted(Formatting.GREEN), false);
        } catch (Throwable t) {
            LOGGER.error("Could not start capture", t);
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            client.player.sendMessage(Text.literal("Parallax capture couldn't start: " + msg).formatted(Formatting.RED), false);
        }
    }

    public static void onPreRender() {
        if (session != null) {
            session.onPreRender();
            if (session.isDone()) session = null;
        }
    }

    public static void onPostRender() {
        if (session != null) {
            session.onPostRender();
            if (session.isDone()) session = null;
        }
    }
}
