package dev.cardrhyme.cinecontrols;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PhotoConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("client-photo-controls.json");

    public boolean overrideTime = false;
    public int timeOfDay = 6000;
    public WeatherMode weather = WeatherMode.SERVER;

    public static PhotoConfig load() {
        if (!Files.exists(PATH)) {
            return new PhotoConfig();
        }

        try {
            PhotoConfig config = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), PhotoConfig.class);
            if (config == null) {
                return new PhotoConfig();
            }
            config.timeOfDay = Math.floorMod(config.timeOfDay, 24000);
            if (config.weather == null) {
                config.weather = WeatherMode.SERVER;
            }
            return config;
        } catch (Exception ignored) {
            return new PhotoConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CineControlsClient.LOGGER.error("Failed to save Client Photo Controls config", e);
        }
    }

    public enum WeatherMode {
        SERVER,
        CLEAR,
        RAIN,
        THUNDER
    }
}
