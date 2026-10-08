package dev.cardrhyme.equirectshot;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EquirectShotClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("EquirectShot");
    public static EquirectConfig CONFIG;

    private static KeyMapping captureKey;
    private static KeyMapping settingsKey;

    @Override
    public void onInitializeClient() {
        CONFIG = EquirectConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(
                ResourceLocation.fromNamespaceAndPath("equirectshot", "main"));

        captureKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.equirectshot.capture", InputConstants.Type.KEYSYM, InputConstants.KEY_F8, category));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.equirectshot.settings", InputConstants.Type.KEYSYM, InputConstants.KEY_F7, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (captureKey.consumeClick()) {
                if (CaptureManager.INSTANCE.isActive()) {
                    CaptureManager.INSTANCE.cancel(client, "Capture cancelled");
                } else {
                    CaptureManager.INSTANCE.start(client);
                }
            }

            while (settingsKey.consumeClick()) {
                if (!CaptureManager.INSTANCE.isActive()) {
                    client.setScreen(new EquirectSettingsScreen(client.screen));
                }
            }
        });
    }

    public static void overlay(Minecraft client, String text) {
        if (client.gui != null) {
            client.gui.setOverlayMessage(Component.literal(text), false);
        }
    }
}
