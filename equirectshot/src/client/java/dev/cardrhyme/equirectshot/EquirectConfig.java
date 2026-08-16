package dev.cardrhyme.equirectshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EquirectConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("equirectshot.json");

    public static final int[] WIDTHS = {1024, 2048, 3072, 4096, 5120, 6144, 7168, 8192, 9216, 10240};

    public int resolutionIndex = 3;
    public double settleSeconds = 1.0;

    public static EquirectConfig load() {
        if (!Files.isRegularFile(PATH)) {
            EquirectConfig config = new EquirectConfig();
            config.save();
            return config;
        }
        try (Reader reader = Files.newBufferedReader(PATH)) {
            EquirectConfig config = GSON.fromJson(reader, EquirectConfig.class);
            if (config == null) config = new EquirectConfig();
            config.sanitize();
            return config;
        } catch (Exception ignored) {
            return new EquirectConfig();
        }
    }

    public void save() {
        sanitize();
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            EquirectShotClient.LOGGER.warn("Could not save EquirectShot config", e);
        }
    }

    public int outputWidth() {
        sanitize();
        return WIDTHS[resolutionIndex];
    }

    public int outputHeight() {
        return outputWidth() / 2;
    }

    public int faceSize() {
        return outputWidth() / 4;
    }

    public void sanitize() {
        resolutionIndex = Math.max(0, Math.min(WIDTHS.length - 1, resolutionIndex));
        if (!Double.isFinite(settleSeconds)) settleSeconds = 1.0;
        settleSeconds = Math.max(0.0, Math.min(5.0, settleSeconds));
        settleSeconds = Math.round(settleSeconds * 10.0) / 10.0;
    }
}
