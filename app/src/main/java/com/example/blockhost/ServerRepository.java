package com.example.blockhost;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Persists BlockHost state exclusively inside Android app-private storage. */
public final class ServerRepository {
    private static final String STATE_FILE = "servers.json";
    private final File root;
    private final File serversRoot;
    private final File backupsRoot;
    private final File stateFile;
    private JSONObject state;

    public ServerRepository(Context context) {
        root = new File(context.getFilesDir(), "blockhost");
        serversRoot = new File(root, "servers");
        backupsRoot = new File(root, "backups");
        stateFile = new File(root, STATE_FILE);
        root.mkdirs(); serversRoot.mkdirs(); backupsRoot.mkdirs();
        load(); ensureInitialServer();
    }

    public synchronized File getRoot() { return root; }
    public synchronized File getServerDir(String id) { return new File(serversRoot, sanitizeId(id)); }
    public synchronized File getRuntimeDir() { return new File(root, "runtime"); }

    private void load() {
        try {
            if (stateFile.isFile()) state = new JSONObject(Files.readString(stateFile.toPath(), StandardCharsets.UTF_8));
            else { state = new JSONObject().put("activeServerId", JSONObject.NULL).put("servers", new JSONArray()); save(); }
        } catch (Exception e) {
            state = new JSONObject();
            try { state.put("activeServerId", JSONObject.NULL).put("servers", new JSONArray()); } catch (JSONException ignored) {}
            save();
        }
    }

    private void ensureInitialServer() {
        try {
            JSONArray servers = state.optJSONArray("servers");
            if (servers == null) { servers = new JSONArray(); state.put("servers", servers); }
            if (servers.length() == 0) {
                JSONObject server = newServerObject("Survival Server", "latest", 1.0, 25565, false);
                servers.put(server); state.put("activeServerId", server.getString("id"));
                initializeServerFiles(server); save();
            }
        } catch (Exception ignored) {}
    }

    private JSONObject newServerObject(String name, String version, double ramGb, int port, boolean eulaAccepted) throws JSONException {
        String id = "srv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new JSONObject()
                .put("id", id).put("name", name).put("edition", "java").put("runtime", "Spigot")
                .put("version", version).put("port", port).put("ramMax", ramGb).put("maxPlayers", 12)
                .put("installed", false).put("eulaAccepted", eulaAccepted).put("createdAt", System.currentTimeMillis())
                .put("lastBackupAt", JSONObject.NULL)
                .put("settings", new JSONObject().put("viewDistance", 8).put("simulationDistance", 6)
                        .put("dynamicView", false).put("pauseEmpty", false).put("allowHighRam", false)
                        .put("autoRestart", true).put("wakeLock", true).put("extraArgs", "nogui").put("backupInterval", "Off"));
    }

    public synchronized JSONObject createServer(JSONObject input) throws Exception {
        String name = input.optString("name", "New Server").trim(); if (name.isEmpty()) name = "New Server";
        String version = input.optString("version", "latest");
        double ram = Math.max(0.5, Math.min(12.0, input.optDouble("ramMax", 1.0)));
        JSONObject server = newServerObject(name, version, ram, input.optInt("port", 25565), input.optBoolean("eulaAccepted", false));
        state.getJSONArray("servers").put(server); state.put("activeServerId", server.getString("id"));
        initializeServerFiles(server); save(); return cloneJson(server);
    }

    private void initializeServerFiles(JSONObject server) throws Exception {
        File dir = getServerDir(server.getString("id")); dir.mkdirs();
        writeText(new File(dir, "eula.txt"), "eula=" + server.optBoolean("eulaAccepted", false) + "\n");
        writeServerProperties(server);
    }

    public synchronized void writeServerProperties(JSONObject server) throws Exception {
        File dir = getServerDir(server.getString("id")); dir.mkdirs();
        JSONObject settings = server.optJSONObject("settings");
        int view = settings == null ? 8 : settings.optInt("viewDistance", 8);
        int sim = settings == null ? 6 : settings.optInt("simulationDistance", 6);
        JSONObject kv = parseProperties(readText(new File(dir, "server.properties")));
        kv.put("server-port", String.valueOf(server.optInt("port", 25565)));
        kv.put("max-players", String.valueOf(server.optInt("maxPlayers", 12)));
        kv.put("view-distance", String.valueOf(view)); kv.put("simulation-distance", String.valueOf(sim));
        kv.put("online-mode", kv.optString("online-mode", "true"));
        kv.put("motd", kv.optString("motd", server.optString("name", "BlockHost Server")));
        kv.put("enable-rcon", kv.optString("enable-rcon", "false")); kv.put("allow-flight", kv.optString("allow-flight", "false"));
        StringBuilder out = new StringBuilder();
        for (String key : iterableKeys(kv)) out.append(key).append('=').append(kv.optString(key, "")).append('\n');
        writeText(new File(dir, "server.properties"), out.toString());
        writeText(new File(dir, "eula.txt"), "eula=" + server.optBoolean("eulaAccepted", false) + "\n");
    }

    private static JSONObject parseProperties(String text) throws JSONException {
        JSONObject result = new JSONObject();
        if (text == null) return result;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim(); if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int idx = line.indexOf('='); if (idx > 0) result.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return result;
    }

    private static Iterable<String> iterableKeys(JSONObject object) {
        List<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add); Collections.sort(keys); return keys;
    }

    public synchronized JSONObject getStateCopy() throws JSONException { return cloneJson(state); }
    public synchronized JSONArray listServers() throws JSONException { return new JSONArray(state.getJSONArray("servers").toString()); }
    public synchronized JSONObject getServer(String id) throws JSONException {
        JSONArray array = state.getJSONArray("servers");
        for (int i = 0; i < array.length(); i++) { JSONObject s = array.getJSONObject(i); if (id.equals(s.optString("id"))) return s; }
        return null;
    }
    public synchronized JSONObject getActiveServer() throws JSONException {
        JSONObject found = getServer(state.optString("activeServerId", ""));
        if (found != null) return found;
        JSONArray servers = state.getJSONArray("servers"); return servers.length() > 0 ? servers.getJSONObject(0) : null;
    }
    public synchronized String getActiveServerId() { return state.optString("activeServerId", ""); }
    public synchronized JSONObject selectServer(String id) throws Exception {
        JSONObject server = getServer(id); if (server == null) throw new IllegalArgumentException("Server not found");
        state.put("activeServerId", id); save(); return cloneJson(server);
    }

    public synchronized JSONObject updateServer(String id, JSONObject changes) throws Exception {
        JSONObject server = getServer(id); if (server == null) throw new IllegalArgumentException("Server not found");
        if (changes.has("name")) { String name = changes.optString("name", "").trim(); if (!name.isEmpty()) server.put("name", name); }
        if (changes.has("version")) server.put("version", changes.getString("version"));
        if (changes.has("ramMax")) server.put("ramMax", Math.max(0.5, Math.min(12.0, changes.getDouble("ramMax"))));
        if (changes.has("port")) server.put("port", changes.getInt("port"));
        if (changes.has("maxPlayers")) server.put("maxPlayers", changes.getInt("maxPlayers"));
        if (changes.has("eulaAccepted")) server.put("eulaAccepted", changes.getBoolean("eulaAccepted"));
        JSONObject settingsChanges = changes.optJSONObject("settings");
        if (settingsChanges != null) {
            JSONObject settings = server.optJSONObject("settings"); if (settings == null) { settings = new JSONObject(); server.put("settings", settings); }
            for (String key : iterableKeys(settingsChanges)) settings.put(key, settingsChanges.get(key));
        }
        writeServerProperties(server); save(); return cloneJson(server);
    }

    public synchronized void setInstalled(String id, boolean installed) throws Exception { JSONObject server = getServer(id); if (server != null) { server.put("installed", installed); save(); } }
    public synchronized void setLastBackup(String id, long timestamp) throws Exception { JSONObject server = getServer(id); if (server != null) { server.put("lastBackupAt", timestamp); save(); } }

    public synchronized void deleteServer(String id) throws Exception {
        JSONArray source = state.getJSONArray("servers"); if (source.length() <= 1) throw new IllegalStateException("At least one server must remain");
        JSONArray target = new JSONArray(); boolean removed = false;
        for (int i = 0; i < source.length(); i++) { JSONObject s = source.getJSONObject(i); if (id.equals(s.optString("id"))) removed = true; else target.put(s); }
        if (!removed) throw new IllegalArgumentException("Server not found");
        state.put("servers", target); if (id.equals(state.optString("activeServerId"))) state.put("activeServerId", target.getJSONObject(0).getString("id"));
        deleteRecursively(getServerDir(id)); deleteRecursively(new File(backupsRoot, sanitizeId(id))); save();
    }

    public synchronized JSONArray listFiles(String id, String relativePath) throws Exception {
        File base = getServerDir(id).getCanonicalFile(); File target = resolveSafe(base, relativePath); JSONArray result = new JSONArray();
        File[] files = target.listFiles(); if (files == null) return result;
        List<File> list = new ArrayList<>(); Collections.addAll(list, files);
        list.sort(Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File file : list) {
            if (file.getName().equals(".blockhost") || file.getName().equals(".build")) continue;
            result.put(new JSONObject().put("name", file.getName()).put("type", file.isDirectory() ? "folder" : "file")
                    .put("size", file.isDirectory() ? folderSize(file) : file.length()).put("modifiedAt", file.lastModified()));
        }
        return result;
    }

    public synchronized String readFile(String id, String relativePath) throws Exception {
        File file = resolveSafe(getServerDir(id).getCanonicalFile(), relativePath);
        if (!file.isFile()) throw new IOException("File not found"); if (file.length() > 2_000_000) throw new IOException("File is too large to edit here");
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }
    public synchronized void writeFile(String id, String relativePath, String content) throws Exception {
        File file = resolveSafe(getServerDir(id).getCanonicalFile(), relativePath); File parent = file.getParentFile(); if (parent != null) parent.mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    public synchronized JSONObject createBackup(String id) throws Exception {
        JSONObject server = getServer(id); if (server == null) throw new IllegalArgumentException("Server not found");
        File source = getServerDir(id), serverBackupDir = new File(backupsRoot, sanitizeId(id)); serverBackupDir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()); File output = new File(serverBackupDir, "backup-" + stamp + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) { zipDirectory(source, source, zip); }
        long now = System.currentTimeMillis(); setLastBackup(id, now);
        return new JSONObject().put("path", output.getAbsolutePath()).put("size", output.length()).put("createdAt", now);
    }

    public synchronized JSONArray readLogEntries(String id, int maxLines) throws Exception {
        File log = new File(getServerDir(id), "logs/blockhost-console.log"); JSONArray output = new JSONArray(); if (!log.isFile()) return output;
        List<String> lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8); int start = Math.max(0, lines.size() - Math.max(1, maxLines));
        for (int i = start; i < lines.size(); i++) { String line = lines.get(i); String kind = line.contains("ERROR") || line.contains("Exception") ? "err" : line.contains("WARN") ? "warn" : line.contains("Done (") ? "ok" : "info"; output.put(new JSONArray().put(kind).put(line)); }
        return output;
    }
    public synchronized void appendLog(String id, String line) {
        try { File logs = new File(getServerDir(id), "logs"); logs.mkdirs(); try (FileWriter writer = new FileWriter(new File(logs, "blockhost-console.log"), true)) { writer.write(line); writer.write('\n'); } } catch (IOException ignored) {}
    }
    public synchronized void clearLog(String id) { File log = new File(getServerDir(id), "logs/blockhost-console.log"); if (log.exists()) log.delete(); }

    private void save() {
        try { root.mkdirs(); File tmp = new File(root, STATE_FILE + ".tmp"); Files.writeString(tmp.toPath(), state.toString(2), StandardCharsets.UTF_8); Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (Exception e) { try { Files.writeString(stateFile.toPath(), state.toString(2), StandardCharsets.UTF_8); } catch (Exception ignored) {} }
    }
    private static JSONObject cloneJson(JSONObject object) throws JSONException { return new JSONObject(object.toString()); }
    private static String sanitizeId(String id) { return id == null ? "invalid" : id.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static File resolveSafe(File base, String relativePath) throws IOException {
        String clean = relativePath == null ? "" : relativePath; while (clean.startsWith("/")) clean = clean.substring(1);
        File result = new File(base, clean).getCanonicalFile(); String basePath = base.getCanonicalPath();
        if (!result.getPath().equals(basePath) && !result.getPath().startsWith(basePath + File.separator)) throw new SecurityException("Invalid path");
        return result;
    }
    private static void writeText(File file, String text) throws IOException { File parent = file.getParentFile(); if (parent != null) parent.mkdirs(); Files.writeString(file.toPath(), text, StandardCharsets.UTF_8); }
    private static String readText(File file) { try { return file.isFile() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : ""; } catch (IOException e) { return ""; } }
    private static long folderSize(File file) { if (file == null || !file.exists()) return 0; if (file.isFile()) return file.length(); long total = 0; File[] children = file.listFiles(); if (children != null) for (File child : children) total += folderSize(child); return total; }
    private static void deleteRecursively(File file) throws IOException { if (file == null || !file.exists()) return; if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteRecursively(child); } if (!file.delete() && file.exists()) throw new IOException("Unable to delete " + file.getName()); }
    private static void zipDirectory(File root, File current, ZipOutputStream zip) throws IOException {
        File[] children = current.listFiles(); if (children == null) return; byte[] buffer = new byte[64 * 1024];
        for (File child : children) { if (child.getName().equals(".build")) continue; String name = root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/');
            if (child.isDirectory()) { zip.putNextEntry(new ZipEntry(name + "/")); zip.closeEntry(); zipDirectory(root, child, zip); }
            else { zip.putNextEntry(new ZipEntry(name)); try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(child))) { int read; while ((read = input.read(buffer)) >= 0) zip.write(buffer, 0, read); } zip.closeEntry(); }
        }
    }
}
