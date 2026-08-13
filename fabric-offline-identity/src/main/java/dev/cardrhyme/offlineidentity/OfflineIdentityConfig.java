package dev.cardrhyme.offlineidentity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class OfflineIdentityConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    public boolean enabled = true;
    public String username = "Player";

    public static OfflineIdentityConfig load() {
        Path instanceConfig = FabricLoader.getInstance().getConfigDir().resolve("offline-identity.json");
        Path globalConfig = Path.of(System.getProperty("user.home"), ".offline-identity", "config.json");
        Path configPath = Files.exists(instanceConfig) ? instanceConfig : globalConfig;

        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                OfflineIdentityConfig defaults = new OfflineIdentityConfig();
                try (Writer writer = Files.newBufferedWriter(configPath)) {
                    GSON.toJson(defaults, writer);
                }
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(configPath)) {
                OfflineIdentityConfig config = GSON.fromJson(reader, OfflineIdentityConfig.class);
                if (config == null) config = new OfflineIdentityConfig();
                config.validate();
                return config;
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Offline Identity] Failed to load config: " + e.getMessage());
            return new OfflineIdentityConfig();
        }
    }

    private void validate() {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username must match [A-Za-z0-9_]{3,16}");
        }
    }
}
