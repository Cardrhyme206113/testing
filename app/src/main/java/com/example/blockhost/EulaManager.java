package com.example.blockhost;

import org.json.JSONObject;

import java.io.File;

/** Keeps the persisted server metadata and eula.txt in agreement. */
public final class EulaManager {
    private EulaManager() {}

    public static synchronized JSONObject reconcile(ServerRepository repository, String serverId) throws Exception {
        JSONObject server = repository.getServer(serverId);
        if (server == null) return null;

        File eulaFile = new File(repository.getServerDir(serverId), "eula.txt");
        boolean metadataAccepted = server.optBoolean("eulaAccepted", false);
        boolean fileAccepted = readAccepted(eulaFile);
        boolean accepted = metadataAccepted || fileAccepted;

        if (accepted != metadataAccepted || (accepted && !fileAccepted)) {
            server = repository.updateServer(serverId, new JSONObject().put("eulaAccepted", accepted));
        }
        return server;
    }

    public static synchronized JSONObject accept(ServerRepository repository, String serverId) throws Exception {
        JSONObject server = repository.getServer(serverId);
        if (server == null) throw new IllegalArgumentException("Server not found");
        return repository.updateServer(serverId, new JSONObject().put("eulaAccepted", true));
    }

    private static boolean readAccepted(File file) {
        try {
            if (!file.isFile()) return false;
            for (String line : FileIo.readUtf8(file).split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                String[] parts = trimmed.split("=", 2);
                if (parts[0].trim().equalsIgnoreCase("eula")) {
                    return parts[1].trim().equalsIgnoreCase("true");
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
