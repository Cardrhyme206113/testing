package com.example.blockhost;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves ready-to-run server JARs. Pufferfish is preferred; Paper is the fallback. */
public final class SpigotVersionProvider {
    public static final String PUFFERFISH_JOB = "https://ci.pufferfish.host/job/Pufferfish-1.21";
    public static final String PAPER_API = "https://fill.papermc.io/v3/projects/paper";
    private static final String USER_AGENT = "BlockHost/0.5.0 (https://github.com/Cardrhyme206113/testing)";
    private static final Pattern PUFFERFISH_VERSION = Pattern.compile(
            "pufferfish-paperclip-([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)-.*\\.jar",
            Pattern.CASE_INSENSITIVE
    );

    private final File cacheFile;

    public SpigotVersionProvider(Context context) {
        File dir = new File(context.getFilesDir(), "blockhost");
        dir.mkdirs();
        cacheFile = new File(dir, "server-versions-cache-v3.json");
    }

    public synchronized JSONArray getVersions(boolean forceRefresh) throws Exception {
        if (!forceRefresh && cacheFile.isFile()
                && System.currentTimeMillis() - cacheFile.lastModified() < 21_600_000L) {
            try { return new JSONArray(FileIo.readUtf8(cacheFile)); } catch (Exception ignored) {}
        }

        try {
            JSONObject project = getObject(PAPER_API, "Paper downloads service");
            JSONObject groups = project.getJSONObject("versions");
            Set<String> unique = new HashSet<>();
            List<String> versions = new ArrayList<>();
            JSONArray groupNames = groups.names();
            if (groupNames != null) {
                for (int i = 0; i < groupNames.length(); i++) {
                    JSONArray group = groups.optJSONArray(groupNames.getString(i));
                    if (group == null) continue;
                    for (int j = 0; j < group.length(); j++) {
                        String version = group.optString(j, "");
                        String lower = version.toLowerCase();
                        if (version.isEmpty() || lower.contains("pre") || lower.contains("rc")
                                || lower.contains("snapshot")) continue;
                        if (unique.add(version)) versions.add(version);
                    }
                }
            }
            versions.sort((a, b) -> compareVersions(b, a));

            JSONArray result = new JSONArray();
            result.put(versionObject("latest"));
            for (String version : versions) result.put(versionObject(version));
            FileIo.writeUtf8(cacheFile, result.toString());
            return result;
        } catch (Exception error) {
            if (cacheFile.isFile()) {
                try { return new JSONArray(FileIo.readUtf8(cacheFile)); } catch (Exception ignored) {}
            }
            JSONArray fallback = new JSONArray();
            for (String version : new String[]{
                    "latest", "1.21.11", "1.21.10", "1.21.8", "1.21.5",
                    "1.21.4", "1.21.1", "1.20.6", "1.20.4"
            }) fallback.put(versionObject(version));
            return fallback;
        }
    }

    public ServerDownload resolveDownload(String requestedVersion) throws Exception {
        String requested = requestedVersion == null || requestedVersion.trim().isEmpty()
                ? "latest" : requestedVersion.trim();

        ServerDownload pufferfish = findPufferfish("latest".equals(requested) ? null : requested);
        if (pufferfish != null) return pufferfish;

        String paperVersion = "latest".equals(requested) ? latestPaperVersion() : requested;
        ServerDownload paper = findPaper(paperVersion);
        if (paper != null) {
            return new ServerDownload(
                    paper.version,
                    paper.build,
                    paper.name,
                    paper.url,
                    paper.sha256,
                    "Paper",
                    "Pufferfish has no ready " + paperVersion + " JAR; using Paper stable instead"
            );
        }

        throw new IllegalStateException(
                "No ready Pufferfish or stable Paper JAR exists for Minecraft " + paperVersion
                        + ". Import your own file named server.jar."
        );
    }

    private ServerDownload findPufferfish(String exactVersion) throws Exception {
        String api = PUFFERFISH_JOB
                + "/api/json?tree=builds[number,result,artifacts[fileName,relativePath]]";
        JSONObject data = getObject(api, "Pufferfish Jenkins");
        JSONArray builds = data.optJSONArray("builds");
        if (builds == null) return null;

        for (int i = 0; i < builds.length(); i++) {
            JSONObject build = builds.optJSONObject(i);
            if (build == null || !"SUCCESS".equals(build.optString("result"))) continue;
            JSONArray artifacts = build.optJSONArray("artifacts");
            if (artifacts == null) continue;

            for (int j = 0; j < artifacts.length(); j++) {
                JSONObject artifact = artifacts.optJSONObject(j);
                if (artifact == null) continue;
                String fileName = artifact.optString("fileName", "");
                String relativePath = artifact.optString("relativePath", "");
                Matcher matcher = PUFFERFISH_VERSION.matcher(fileName);
                if (!matcher.matches()) continue;
                String version = matcher.group(1);
                if (exactVersion != null && !exactVersion.equals(version)) continue;
                int number = build.optInt("number", 0);
                String url = PUFFERFISH_JOB + "/" + number + "/artifact/" + relativePath;
                return new ServerDownload(
                        version, number, fileName, url, "", "Pufferfish", ""
                );
            }
        }
        return null;
    }

    private ServerDownload findPaper(String version) throws Exception {
        JSONArray builds = getArray(
                PAPER_API + "/versions/" + version + "/builds",
                "Paper downloads service"
        );
        JSONObject selected = null;
        for (int i = 0; i < builds.length(); i++) {
            JSONObject build = builds.optJSONObject(i);
            if (build == null) continue;
            if ("STABLE".equalsIgnoreCase(build.optString("channel"))) {
                selected = build;
                break;
            }
            if (selected == null) selected = build;
        }
        if (selected == null) return null;

        JSONObject downloads = selected.optJSONObject("downloads");
        JSONObject server = downloads == null ? null : downloads.optJSONObject("server:default");
        if (server == null) return null;
        JSONObject checksums = server.optJSONObject("checksums");
        return new ServerDownload(
                version,
                selected.optInt("id", selected.optInt("number", 0)),
                server.optString("name", "paper-" + version + ".jar"),
                server.getString("url"),
                checksums == null ? "" : checksums.optString("sha256", ""),
                "Paper",
                ""
        );
    }

    private String latestPaperVersion() throws Exception {
        JSONObject project = getObject(PAPER_API, "Paper downloads service");
        JSONObject groups = project.getJSONObject("versions");
        List<String> versions = new ArrayList<>();
        JSONArray names = groups.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                JSONArray group = groups.optJSONArray(names.getString(i));
                if (group == null) continue;
                for (int j = 0; j < group.length(); j++) {
                    String version = group.optString(j, "");
                    if (!version.isEmpty()) versions.add(version);
                }
            }
        }
        versions.sort((a, b) -> compareVersions(b, a));
        if (versions.isEmpty()) throw new IllegalStateException("Paper returned no versions");
        return versions.get(0);
    }

    private static JSONObject getObject(String url, String service) throws Exception {
        String body = getText(url, service);
        return new JSONObject(body);
    }

    private static JSONArray getArray(String url, String service) throws Exception {
        String body = getText(url, service);
        return new JSONArray(body);
    }

    private static String getText(String url, String service) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(90_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException(service + " returned HTTP " + code);
        }
        try { return FileIo.readUtf8(connection.getInputStream()); }
        finally { connection.disconnect(); }
    }

    private static JSONObject versionObject(String version) throws Exception {
        return new JSONObject()
                .put("version", version)
                .put("source", "pufferfish-preferred")
                .put("javaMajor", requiredJavaMajor(version));
    }

    public static int requiredJavaMajor(String version) {
        if ("latest".equals(version)) return 21;
        int[] p = parseVersion(version);
        int minor = p.length > 1 ? p[1] : 0;
        int patch = p.length > 2 ? p[2] : 0;
        if (minor > 20 || (minor == 20 && patch >= 5)) return 21;
        if (minor >= 17) return 17;
        return 8;
    }

    private static int compareVersions(String a, String b) {
        int[] x = parseVersion(a), y = parseVersion(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xv = i < x.length ? x[i] : 0;
            int yv = i < y.length ? y[i] : 0;
            if (xv != yv) return Integer.compare(xv, yv);
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        String[] tokens = version.replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try { out[i] = Integer.parseInt(tokens[i]); } catch (Exception ignored) {}
        }
        return out;
    }

    public static final class ServerDownload {
        public final String version, name, url, sha256, source, note;
        public final int build;

        ServerDownload(String version, int build, String name, String url,
                       String sha256, String source, String note) {
            this.version = version;
            this.build = build;
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
            this.source = source;
            this.note = note;
        }
    }
}
