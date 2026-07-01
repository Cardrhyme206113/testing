package com.example.blockhost;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Paper version/download provider. Paper distributes ready server JARs. */
public final class SpigotVersionProvider {
    public static final String PAPER_API = "https://api.papermc.io/v2/projects/paper";
    private final File cacheFile;

    public SpigotVersionProvider(Context context) {
        File dir = new File(context.getFilesDir(), "blockhost");
        dir.mkdirs();
        cacheFile = new File(dir, "paper-versions-cache.json");
    }

    public synchronized JSONArray getVersions(boolean forceRefresh) throws Exception {
        if (!forceRefresh && cacheFile.isFile() && System.currentTimeMillis() - cacheFile.lastModified() < 21_600_000L) {
            try { return new JSONArray(FileIo.readUtf8(cacheFile)); } catch (Exception ignored) {}
        }
        try {
            JSONObject project = getJson(PAPER_API);
            JSONArray source = project.getJSONArray("versions");
            List<String> stable = new ArrayList<>();
            for (int i = 0; i < source.length(); i++) {
                String version = source.getString(i);
                if (!version.contains("pre") && !version.contains("rc")) stable.add(version);
            }
            stable.sort((a, b) -> compareVersions(b, a));
            JSONArray result = new JSONArray();
            result.put(versionObject("latest"));
            for (String version : stable) result.put(versionObject(version));
            FileIo.writeUtf8(cacheFile, result.toString());
            return result;
        } catch (Exception error) {
            if (cacheFile.isFile()) return new JSONArray(FileIo.readUtf8(cacheFile));
            JSONArray fallback = new JSONArray();
            for (String version : new String[]{"latest","1.21.11","1.21.10","1.21.8","1.21.5","1.21.4","1.21.1","1.20.6","1.20.4"}) fallback.put(versionObject(version));
            return fallback;
        }
    }

    public PaperDownload resolveDownload(String requestedVersion) throws Exception {
        String version = requestedVersion == null || requestedVersion.isEmpty() ? "latest" : requestedVersion;
        if ("latest".equals(version)) {
            JSONObject project = getJson(PAPER_API);
            JSONArray versions = project.getJSONArray("versions");
            for (int i = versions.length() - 1; i >= 0; i--) {
                String candidate = versions.getString(i);
                if (!candidate.contains("pre") && !candidate.contains("rc")) { version = candidate; break; }
            }
        }
        JSONObject versionInfo = getJson(PAPER_API + "/versions/" + version);
        JSONArray builds = versionInfo.getJSONArray("builds");
        if (builds.length() == 0) throw new IllegalStateException("Paper has no builds for Minecraft " + version);
        int build = builds.getInt(builds.length() - 1);
        JSONObject buildInfo = getJson(PAPER_API + "/versions/" + version + "/builds/" + build);
        JSONObject application = buildInfo.getJSONObject("downloads").getJSONObject("application");
        String name = application.getString("name");
        String sha256 = application.optString("sha256", "");
        String url = PAPER_API + "/versions/" + version + "/builds/" + build + "/downloads/" + name;
        return new PaperDownload(version, build, name, url, sha256);
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "BlockHost-Android/0.4");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("Paper API returned HTTP " + code);
        try { return new JSONObject(FileIo.readUtf8(connection.getInputStream())); }
        finally { connection.disconnect(); }
    }

    private static JSONObject versionObject(String version) throws Exception {
        return new JSONObject().put("version", version).put("source", "paper").put("javaMajor", requiredJavaMajor(version));
    }

    public static int requiredJavaMajor(String version) {
        if ("latest".equals(version)) return 21;
        int[] p = parseVersion(version);
        int minor = p.length > 1 ? p[1] : 0, patch = p.length > 2 ? p[2] : 0;
        if (minor > 20 || (minor == 20 && patch >= 5)) return 21;
        if (minor >= 17) return 17;
        return 8;
    }

    private static int compareVersions(String a, String b) {
        int[] x = parseVersion(a), y = parseVersion(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xv = i < x.length ? x[i] : 0, yv = i < y.length ? y[i] : 0;
            if (xv != yv) return Integer.compare(xv, yv);
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        String[] tokens = version.replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) try { out[i] = Integer.parseInt(tokens[i]); } catch (Exception ignored) {}
        return out;
    }

    public static final class PaperDownload {
        public final String version, name, url, sha256;
        public final int build;
        PaperDownload(String version, int build, String name, String url, String sha256) {
            this.version = version; this.build = build; this.name = name; this.url = url; this.sha256 = sha256;
        }
    }
}
