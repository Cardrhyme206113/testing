package com.example.blockhost;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpigotVersionProvider {
    public static final String INDEX_URL = "https://hub.spigotmc.org/versions/";
    public static final String BUILDTOOLS_URL = "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar";
    private static final Pattern VERSION_LINK = Pattern.compile("href=\\\"([0-9][0-9A-Za-z._-]*)\\.json\\\"");
    private final File cacheFile;

    public SpigotVersionProvider(Context context) {
        File dir = new File(context.getFilesDir(), "blockhost");
        dir.mkdirs();
        cacheFile = new File(dir, "spigot-versions-cache.json");
    }

    public synchronized JSONArray getVersions(boolean forceRefresh) throws Exception {
        if (!forceRefresh && cacheFile.isFile() && System.currentTimeMillis() - cacheFile.lastModified() < 21600000L) {
            try { return new JSONArray(FileIo.readUtf8(cacheFile)); } catch (Exception ignored) {}
        }
        try {
            JSONArray versions = fetchOfficialVersions();
            FileIo.writeUtf8(cacheFile, versions.toString());
            return versions;
        } catch (Exception networkError) {
            if (cacheFile.isFile()) try { return new JSONArray(FileIo.readUtf8(cacheFile)); } catch (Exception ignored) {}
            JSONArray fallback = new JSONArray();
            String[] known = {"latest","1.21.11","1.21.10","1.21.8","1.21.5","1.21.4","1.21.1","1.20.6","1.20.4","1.19.4","1.18.2"};
            for (String version : known) fallback.put(versionObject(version, "fallback"));
            return fallback;
        }
    }

    private JSONArray fetchOfficialVersions() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(INDEX_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "BlockHost-Android/0.2");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("Spigot version index returned HTTP " + code);
        String html;
        try { html = FileIo.readUtf8(connection.getInputStream()); }
        finally { connection.disconnect(); }
        Set<String> unique = new LinkedHashSet<>();
        Matcher matcher = VERSION_LINK.matcher(html);
        while (matcher.find()) {
            String version = matcher.group(1);
            if (version.matches("\\d+\\.\\d+(?:\\.\\d+)?")) unique.add(version);
        }
        if (unique.isEmpty()) throw new IllegalStateException("No versions were found in the official Spigot index");
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort((a,b)->compareVersions(b,a));
        JSONArray result = new JSONArray();
        result.put(versionObject("latest", "official"));
        for (String version : sorted) {
            JSONObject item = versionObject(version, "official");
            item.put("legacy", requiredJavaMajor(version) < 17);
            result.put(item);
        }
        return result;
    }

    private static JSONObject versionObject(String version, String source) throws Exception {
        return new JSONObject().put("version", version).put("source", source).put("javaMajor", requiredJavaMajor(version));
    }
    public static int requiredJavaMajor(String version) {
        if ("latest".equals(version)) return 21;
        int[] parts = parseVersion(version);
        int minor = parts.length > 1 ? parts[1] : 0;
        int patch = parts.length > 2 ? parts[2] : 0;
        if (minor > 20 || (minor == 20 && patch >= 5)) return 21;
        if (minor >= 17) return 17;
        return 8;
    }
    private static int compareVersions(String a,String b) {
        int[] av=parseVersion(a),bv=parseVersion(b);
        for(int i=0;i<Math.max(av.length,bv.length);i++){int ai=i<av.length?av[i]:0,bi=i<bv.length?bv[i]:0;if(ai!=bi)return Integer.compare(ai,bi);}return 0;
    }
    private static int[] parseVersion(String version) {
        String[] tokens=version.replaceAll("[^0-9.]","").split("\\.");int[] out=new int[tokens.length];
        for(int i=0;i<tokens.length;i++)try{out[i]=Integer.parseInt(tokens[i]);}catch(Exception ignored){}return out;
    }
}
