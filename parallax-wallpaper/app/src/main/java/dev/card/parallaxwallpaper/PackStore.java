package dev.card.parallaxwallpaper;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PackStore {
    private PackStore() {}
    public static final String PREFS = "parallax_prefs";
    public static final String CURRENT = "current_scene";

    public static final class ImportResult {
        public final File dir;
        public final int triangles;
        ImportResult(File dir, int triangles) { this.dir = dir; this.triangles = triangles; }
    }

    public static File currentScene(Context c) {
        String p = c.getSharedPreferences(PREFS, 0).getString(CURRENT, null);
        return p == null ? null : new File(p);
    }

    public static ImportResult importScene(Context c, Uri uri) throws Exception {
        File cache = new File(c.getCacheDir(), "scene-import.zip");
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(cache))) {
            if (in == null) throw new IOException("Could not open selected file");
            copy(in, out);
        }
        return importZip(c, cache);
    }

    private static ImportResult importZip(Context c, File zipPath) throws Exception {
        try (ZipFile zf = new ZipFile(zipPath)) {
            ZipEntry standardized = zf.getEntry("pack.json");
            if (standardized != null) return importParallaxPack(c, zf, standardized);

            ZipEntry meshManifestEntry = zf.getEntry("mesh/global_mesh_manifest.json");
            ZipEntry meshEntry = zf.getEntry("mesh/global_mesh.bin.zst");
            if (meshManifestEntry == null || meshEntry == null)
                throw new IOException("Not a supported fused-mesh scene ZIP");

            JSONObject mm = new JSONObject(readText(zf, meshManifestEntry));
            if (!"global-triangle-mesh-f32-v3".equals(mm.optString("format")))
                throw new IOException("Unsupported mesh format: " + mm.optString("format"));
            int rawBytes = mm.optInt("raw_mesh_bytes", -1);
            int triangles = mm.getInt("triangle_count");
            int fpv = mm.optInt("floats_per_vertex", 7);
            if (rawBytes <= 0) rawBytes = mm.getInt("vertex_count") * fpv * 4;

            JSONArray triPer = mm.getJSONArray("triangles_per_source");
            JSONArray beautySources = mm.getJSONArray("beauty_texture_source_files");

            float fov = 70f;
            ZipEntry metaEntry = zf.getEntry("metadata.json");
            if (metaEntry != null) {
                JSONObject meta = new JSONObject(readText(zf, metaEntry));
                fov = (float) meta.optDouble("renderedFov", meta.optDouble("fov", 70.0));
            }

            String id = sha1(zipPath) + "-" + triangles;
            File dir = new File(new File(c.getFilesDir(), "scenes"), id);
            resetDir(dir);
            extract(zf, meshEntry, new File(dir, "mesh.bin.zst"));

            JSONArray textures = new JSONArray();
            for (int i = 0; i < beautySources.length(); i++) {
                String wanted = beautySources.getString(i);
                String base = basenameNoExt(wanted);
                ZipEntry found = findBeauty(zf, base);
                if (found == null) throw new IOException("Missing beauty texture for " + wanted);
                String ext = extension(found.getName());
                String local = "textures/" + i + (ext.isEmpty() ? ".jpg" : "." + ext);
                File dst = new File(dir, local);
                dst.getParentFile().mkdirs();
                extract(zf, found, dst);
                textures.put(local);
            }

            JSONObject pack = new JSONObject();
            pack.put("format", "parallax-pack-v1");
            pack.put("sourceFormat", mm.getString("format"));
            pack.put("coordinateSpace", "+X right, +Y up, +Z forward; base camera at origin");
            pack.put("floatsPerVertex", fpv);
            pack.put("triangleCount", triangles);
            pack.put("rawMeshBytes", rawBytes);
            pack.put("trianglesPerSource", triPer);
            pack.put("drawLayerOrder", mm.optJSONArray("ownership_order_layers") != null ? mm.getJSONArray("ownership_order_layers") : defaultLayerOrder(triPer.length()));
            pack.put("textures", textures);
            int centerLayer = 0; for (int i=0;i<beautySources.length();i++) { String n=beautySources.getString(i); if (n.contains("r+0_u+0") || n.contains("pose_00")) { centerLayer=i; break; } }
            pack.put("centerTextureLayer", centerLayer);
            pack.put("textureAspect", 1920.0/1200.0);
            pack.put("verticalFovDegrees", fov);
            pack.put("maxParallaxWorld", 0.32);
            writeText(new File(dir, "pack.json"), pack.toString(2));

            setCurrent(c, dir);
            return new ImportResult(dir, triangles);
        }
    }

    private static ImportResult importParallaxPack(Context c, ZipFile zf, ZipEntry manifestEntry) throws Exception {
        JSONObject pack = new JSONObject(readText(zf, manifestEntry));
        if (!"parallax-pack-v1".equals(pack.optString("format")))
            throw new IOException("Unsupported ParallaxPack version");
        int triangles = pack.getInt("triangleCount");
        File dir = new File(new File(c.getFilesDir(), "scenes"), "pack-" + UUID.randomUUID());
        resetDir(dir);
        extract(zf, manifestEntry, new File(dir, "pack.json"));
        extractRequired(zf, "mesh.bin.zst", new File(dir, "mesh.bin.zst"));
        JSONArray tex = pack.getJSONArray("textures");
        for (int i = 0; i < tex.length(); i++) {
            String p = tex.getString(i);
            File dst = new File(dir, p);
            dst.getParentFile().mkdirs();
            extractRequired(zf, p, dst);
        }
        setCurrent(c, dir);
        return new ImportResult(dir, triangles);
    }

    private static void setCurrent(Context c, File dir) {
        c.getSharedPreferences(PREFS, 0).edit().putString(CURRENT, dir.getAbsolutePath()).apply();
    }

    public static JSONObject readPack(File dir) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(new File(dir, "pack.json")));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out);
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
    }

    private static JSONArray defaultLayerOrder(int n) { JSONArray a = new JSONArray(); for (int i=0;i<n;i++) a.put(i); return a; }

    private static ZipEntry findBeauty(ZipFile zf, String base) {
        Enumeration<? extends ZipEntry> e = zf.entries();
        while (e.hasMoreElements()) {
            ZipEntry ze = e.nextElement();
            if (!ze.isDirectory() && ze.getName().startsWith("beauty/") && basenameNoExt(ze.getName()).equals(base)) return ze;
        }
        return null;
    }
    private static String basenameNoExt(String s) {
        int slash = s.lastIndexOf('/'); String n = slash >= 0 ? s.substring(slash + 1) : s;
        int dot = n.lastIndexOf('.'); return dot > 0 ? n.substring(0, dot) : n;
    }
    private static String extension(String s) {
        int dot = s.lastIndexOf('.'); return dot >= 0 ? s.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
    private static void extractRequired(ZipFile zf, String name, File dst) throws IOException {
        ZipEntry ze = zf.getEntry(name); if (ze == null) throw new IOException("Missing " + name); extract(zf, ze, dst);
    }
    private static void extract(ZipFile zf, ZipEntry ze, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (InputStream in = new BufferedInputStream(zf.getInputStream(ze)); OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) { copy(in, out); }
    }
    private static String readText(ZipFile zf, ZipEntry ze) throws IOException {
        try (InputStream in = zf.getInputStream(ze)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private static void writeText(File f, String s) throws IOException {
        f.getParentFile().mkdirs(); try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(s); }
    }
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1024 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }
    private static String sha1(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[1024 * 1024]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        StringBuilder sb = new StringBuilder(); for (byte x : md.digest()) sb.append(String.format("%02x", x)); return sb.substring(0, 16);
    }
    private static void resetDir(File d) throws IOException {
        if (d.exists()) deleteRec(d); if (!d.mkdirs() && !d.isDirectory()) throw new IOException("Could not create scene directory");
    }
    private static void deleteRec(File f) {
        File[] c = f.listFiles(); if (c != null) for (File x : c) deleteRec(x); f.delete();
    }
}
