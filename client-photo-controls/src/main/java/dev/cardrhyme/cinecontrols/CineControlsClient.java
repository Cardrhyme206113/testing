package dev.cardrhyme.cinecontrols;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CineControlsClient implements ClientModInitializer {
    public static final String MOD_ID = "client_photo_controls";
    public static final Logger LOGGER = LoggerFactory.getLogger("Client Photo Controls");
    public static final PhotoConfig CONFIG = PhotoConfig.load();

    @Override
    public void onInitializeClient() {
        // Visual overrides are applied by client-only Level mixins.
    }
}
