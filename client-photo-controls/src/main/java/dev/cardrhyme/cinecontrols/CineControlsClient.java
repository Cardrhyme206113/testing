package dev.cardrhyme.cinecontrols;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CineControlsClient implements ClientModInitializer {
    public static final String MOD_ID = "client_photo_controls";
    public static final Logger LOGGER = LoggerFactory.getLogger("Client Photo Controls");
    public static final PhotoConfig CONFIG = PhotoConfig.load();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(CineControlsClient::applyWeatherOverride);
    }

    private static void applyWeatherOverride(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }

        switch (CONFIG.weather) {
            case SERVER -> {
                // Leave the server-provided weather untouched.
            }
            case CLEAR -> {
                level.setRainLevel(0.0f);
                level.setThunderLevel(0.0f);
            }
            case RAIN -> {
                level.setRainLevel(1.0f);
                level.setThunderLevel(0.0f);
            }
            case THUNDER -> {
                level.setRainLevel(1.0f);
                level.setThunderLevel(1.0f);
            }
        }
    }
}
