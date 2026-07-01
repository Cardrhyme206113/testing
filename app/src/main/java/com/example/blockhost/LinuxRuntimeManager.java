package com.example.blockhost;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Downloads an Android-native OpenJDK 21 runtime; no Alpine or PRoot. */
public final class LinuxRuntimeManager {
    public interface ProgressListener { void onProgress(String phase, int percent, String message); }

    private static final String RUNTIME_URL = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre21/jre21-android-arm64.tar.xz";
    private final Context context;
    private final File runtimeDir;
    private final File javaHome;
    private final File archive;

    public LinuxRuntimeManager(Context context, ServerRepository repository) {
        this.context = context.getApplicationContext();
        runtimeDir = repository.getRuntimeDir();
        javaHome = new File(runtimeDir, "mobile-jre21");
        archive = new File(runtimeDir, "jre21-android-arm64.tar.xz");
    }

    public synchronized Layout ensureReady(ProgressListener listener) throws Exception {
        verifyArm64();
        Layout layout = inspect();
        if (layout != null) return layout;
        runtimeDir.mkdirs();
        if (!archive.isFile() || archive.length() < 10_000_000L) download(listener);
        delete(javaHome); javaHome.mkdirs();
        listener.onProgress("java-extract", 60, "Extracting Android Java 21");
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive));
             XZCompressorInputStream xz = new XZCompressorInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
            extract(tar, javaHome, listener);
        }
        File[] top = javaHome.listFiles();
        if (top != null && top.length == 1 && top[0].isDirectory() && new File(top[0], "release").isFile()) {
            File promoted = new File(runtimeDir, "mobile-jre21-promoted");
            delete(promoted);
            if (!top[0].renameTo(promoted)) Files.move(top[0].toPath(), promoted.toPath());
            delete(javaHome);
            if (!promoted.renameTo(javaHome)) Files.move(promoted.toPath(), javaHome.toPath());
        }
        layout = inspect();
        if (layout == null) throw new IOException("Android Java runtime is incomplete");
        archive.delete();
        deleteQuietly(new File(runtimeDir, "alpine"));
        deleteQuietly(new File(runtimeDir, "alpine-bundled-v1"));
        deleteQuietly(new File(runtimeDir, "alpine-download-v1"));
        listener.onProgress("java-ready", 100, "Android Java 21 ready");
        return layout;
    }

    public Layout inspect() {
        if (!new File(javaHome, "release").isFile()) return null;
        File jli = find(javaHome, "libjli.so"), jvm = find(javaHome, "libjvm.so");
        if (jli == null || jvm == null) return null;
        List<File> libs = new ArrayList<>(); collect(javaHome, libs);
        libs.sort(Comparator.comparingInt(f -> rank(f.getName())));
        LinkedHashSet<String> dirs = new LinkedHashSet<>();
        dirs.add(jvm.getParent()); dirs.add(jli.getParent());
        for (File lib : libs) dirs.add(lib.getParent());
        dirs.add(context.getApplicationInfo().nativeLibraryDir);
        dirs.add("/system/lib64"); dirs.add("/vendor/lib64");
        return new Layout(javaHome, jli, libs.toArray(new File[0]), String.join(":", dirs));
    }

    private void download(ProgressListener listener) throws Exception {
        File part = new File(archive.getPath() + ".part"); part.delete();
        HttpURLConnection c = (HttpURLConnection) new URL(RUNTIME_URL).openConnection();
        c.setConnectTimeout(30_000); c.setReadTimeout(120_000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "BlockHost-Android/0.4");
        if (c.getResponseCode() / 100 != 2) throw new IOException("Java download HTTP " + c.getResponseCode());
        long total = c.getContentLengthLong(), done = 0; int last = -1;
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            byte[] buf = new byte[262144]; int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n); done += n;
                int p = total > 0 ? 2 + (int)Math.min(56, done * 56 / total) : 20;
                if (p != last) { last = p; listener.onProgress("java-download", p, "Downloading Android Java: " + human(done) + (total > 0 ? " / " + human(total) : "")); }
            }
        } finally { c.disconnect(); }
        if (!part.renameTo(archive)) Files.move(part.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void extract(TarArchiveInputStream tar, File root, ProgressListener listener) throws Exception {
        String rootPath = root.getCanonicalPath(); long count = 0; TarArchiveEntry entry;
        while ((entry = tar.getNextTarEntry()) != null) {
            String name = entry.getName().replace('\\', '/'); while (name.startsWith("./")) name = name.substring(2);
            File out = new File(root, name).getCanonicalFile();
            if (!out.getPath().equals(rootPath) && !out.getPath().startsWith(rootPath + File.separator)) throw new SecurityException("Unsafe Java archive path");
            File parent = out.getParentFile(); if (parent != null) parent.mkdirs();
            if (entry.isDirectory()) out.mkdirs();
            else if (entry.isSymbolicLink()) { Files.deleteIfExists(out.toPath()); Os.symlink(entry.getLinkName(), out.getAbsolutePath()); }
            else if (entry.isFile()) try (OutputStream file = new BufferedOutputStream(new FileOutputStream(out))) { byte[] buf = new byte[131072]; int n; while ((n = tar.read(buf)) >= 0) file.write(buf, 0, n); }
            if (!entry.isSymbolicLink()) try { Os.chmod(out.getAbsolutePath(), entry.getMode()); } catch (Exception ignored) {}
            if (++count % 300 == 0) listener.onProgress("java-extract", Math.min(96, 60 + (int)(count / 70)), "Extracting Android Java… " + count + " files");
        }
    }

    private static File find(File root, String name) { File[] fs = root.listFiles(); if (fs == null) return null; for (File f : fs) { if (f.isFile() && f.getName().equals(name)) return f; if (f.isDirectory()) { File x = find(f, name); if (x != null) return x; } } return null; }
    private static void collect(File root, List<File> out) { File[] fs = root.listFiles(); if (fs == null) return; for (File f : fs) { if (f.isDirectory()) collect(f, out); else if (f.getName().endsWith(".so")) out.add(f); } }
    private static int rank(String n) { if (n.equals("libjli.so")) return 0; if (n.equals("libjvm.so")) return 1; if (n.equals("libverify.so")) return 2; if (n.equals("libjava.so")) return 3; return 100; }
    private static void deleteQuietly(File f) { try { delete(f); } catch (Exception ignored) {} }
    private static void delete(File f) throws IOException { if (f == null || !f.exists()) return; if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) { File[] fs=f.listFiles(); if(fs!=null) for(File x:fs) delete(x); } Files.deleteIfExists(f.toPath()); }
    private static void verifyArm64() { for(String abi:Build.SUPPORTED_ABIS) if("arm64-v8a".equals(abi)) return; throw new IllegalStateException("ARM64 Android is required"); }
    private static String human(long b) { double v=b; String[] u={"B","KB","MB","GB"}; int i=0; while(v>=1024&&i<u.length-1){v/=1024;i++;} return String.format(Locale.US,i==0?"%.0f %s":"%.1f %s",v,u[i]); }

    public static final class Layout {
        public final File javaHome, libjli; public final File[] preloadLibraries; public final String libraryPath;
        Layout(File h, File j, File[] p, String l) { javaHome=h; libjli=j; preloadLibraries=p; libraryPath=l; }
    }
}
