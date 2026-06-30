package com.example.blockhost;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates an app-private Alpine Linux environment and executes it through the bundled Termux PRoot binary.
 * The rootfs and all Minecraft data live under Context.getFilesDir(), so Android removes them on uninstall.
 */
public final class LinuxRuntimeManager {
    public interface ProgressListener {
        void onProgress(String phase, int percent, String message);
    }

    private static final String ALPINE_RELEASES_URL = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml";
    private static final String ALPINE_RELEASES_BASE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/";

    private final Context context;
    private final File runtimeDir;
    private final File rootfsDir;
    private final File readyMarker;

    public LinuxRuntimeManager(Context context, ServerRepository repository) {
        this.context = context.getApplicationContext();
        runtimeDir = repository.getRuntimeDir();
        rootfsDir = new File(runtimeDir, "alpine");
        readyMarker = new File(runtimeDir, ".ready-v2");
    }

    public boolean isReady() {
        return readyMarker.isFile() && new File(rootfsDir, "usr/bin/java").exists();
    }

    public synchronized void ensureReady(ProgressListener listener) throws Exception {
        if (isReady()) {
            listener.onProgress("runtime", 100, "Linux Java runtime ready");
            return;
        }
        verifyArchitecture();
        runtimeDir.mkdirs();
        if (!new File(rootfsDir, "bin/sh").exists()) {
            listener.onProgress("runtime-download", 1, "Finding the current Alpine ARM64 image");
            String archiveName = discoverAlpineArchive();
            File archive = new File(runtimeDir, archiveName);
            download(ALPINE_RELEASES_BASE + archiveName, archive, "runtime-download", listener);
            listener.onProgress("runtime-extract", 45, "Extracting Linux runtime");
            deleteRecursively(rootfsDir);
            rootfsDir.mkdirs();
            extractTarGz(archive, rootfsDir, listener);
            archive.delete();
            writeRuntimeConfiguration();
        }
        listener.onProgress("runtime-packages", 70, "Installing Java, Git and build tools (first install only)");
        String installCommand = "apk update && " +
                "apk add --no-cache bash curl ca-certificates git maven openjdk21-jdk openjdk17-jdk || " +
                "apk add --no-cache bash curl ca-certificates git maven openjdk21-jdk";
        Process process = startShell(null, installCommand);
        String output = ProcessIo.consume(process, line -> listener.onProgress("runtime-packages", 80, line));
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("Runtime package installation failed (exit " + exit + "): " + tail(output, 1200));
        FileIo.writeUtf8(readyMarker, "ready\n");
        listener.onProgress("runtime", 100, "Linux Java runtime ready");
    }

    public Process startShell(File serverDir, String shellCommand) throws IOException {
        List<String> command = baseProotCommand(serverDir);
        command.add("/usr/bin/env");
        command.add("-i");
        command.add("HOME=/root");
        command.add("USER=root");
        command.add("LANG=C.UTF-8");
        command.add("TERM=xterm-256color");
        command.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        command.add("SHELL=/bin/bash");
        command.add("/bin/bash");
        command.add("-lc");
        command.add(shellCommand);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        env.put("LD_LIBRARY_PATH", nativeDir);
        env.put("PROOT_LOADER", new File(nativeDir, "libproot_loader.so").getAbsolutePath());
        File loader32 = new File(nativeDir, "libproot_loader32.so");
        if (loader32.exists()) env.put("PROOT_LOADER_32", loader32.getAbsolutePath());
        File tmp = new File(context.getCacheDir(), "proot-tmp");
        tmp.mkdirs();
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        return builder.start();
    }

    public String javaCommandForVersion(String version) {
        int major = SpigotVersionProvider.requiredJavaMajor(version);
        if (major >= 21) return "/usr/lib/jvm/java-21-openjdk/bin/java";
        if (major >= 17 && new File(rootfsDir, "usr/lib/jvm/java-17-openjdk/bin/java").exists()) {
            return "/usr/lib/jvm/java-17-openjdk/bin/java";
        }
        if (major < 17) return "/usr/lib/jvm/java-17-openjdk/bin/java";
        return "/usr/bin/java";
    }

    private List<String> baseProotCommand(File serverDir) throws IOException {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot_exec.so");
        if (!proot.isFile()) throw new IOException("Bundled PRoot executable is missing for this CPU architecture");
        if (!rootfsDir.isDirectory()) throw new IOException("Linux runtime has not been installed");
        List<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("--kill-on-exit");
        args.add("-0");
        args.add("-r");
        args.add(rootfsDir.getAbsolutePath());
        args.add("-b"); args.add("/dev");
        args.add("-b"); args.add("/proc");
        args.add("-b"); args.add("/sys");
        args.add("-b"); args.add("/sdcard");
        if (serverDir != null) {
            serverDir.mkdirs();
            args.add("-b"); args.add(serverDir.getAbsolutePath() + ":/server");
            args.add("-w"); args.add("/server");
        } else {
            args.add("-w"); args.add("/root");
        }
        return args;
    }

    private void verifyArchitecture() {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi)) arm64 = true;
        if (!arm64) throw new IllegalStateException("This experimental backend currently supports ARM64 Android devices only");
    }

    private String discoverAlpineArchive() throws Exception {
        String yaml = httpGet(ALPINE_RELEASES_URL);
        String fallback = null;
        for (String line : yaml.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("file:")) continue;
            String value = trimmed.substring(5).trim().replace("\"", "").replace("'", "");
            if (value.contains("alpine-minirootfs") && value.endsWith("-aarch64.tar.gz")) {
                if (fallback == null) fallback = value;
                if (!value.contains("-rc")) return value;
            }
        }
        if (fallback != null) return fallback;
        throw new IOException("Unable to find an Alpine ARM64 minirootfs");
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(40_000);
        connection.setRequestProperty("User-Agent", "BlockHost-Android/0.2");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " from " + url);
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            return FileIo.readUtf8(input);
        } finally {
            connection.disconnect();
        }
    }

    private void download(String url, File output, String phase, ProgressListener listener) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "BlockHost-Android/0.2");
        connection.setInstanceFollowRedirects(true);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("Download failed with HTTP " + code);
        long total = connection.getContentLengthLong();
        output.getParentFile().mkdirs();
        File partial = new File(output.getParentFile(), output.getName() + ".part");
        byte[] buffer = new byte[128 * 1024];
        long done = 0;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(partial))) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                done += read;
                int percent = total > 0 ? (int) Math.min(44, 2 + done * 42 / total) : 10;
                listener.onProgress(phase, percent, "Downloading Linux runtime: " + humanBytes(done) + (total > 0 ? " / " + humanBytes(total) : ""));
            }
        } finally {
            connection.disconnect();
        }
        if (!partial.renameTo(output)) {
            Files.move(partial.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void extractTarGz(File archive, File destination, ProgressListener listener) throws Exception {
        long processed = 0;
        try (InputStream fileInput = new BufferedInputStream(new FileInputStream(archive));
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(fileInput);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                File target = new File(destination, name).getCanonicalFile();
                String destinationPath = destination.getCanonicalPath();
                if (!target.getPath().startsWith(destinationPath + File.separator) && !target.equals(destination)) {
                    throw new SecurityException("Unsafe archive path: " + name);
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else if (entry.isSymbolicLink()) {
                    File parent = target.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try { Os.symlink(entry.getLinkName(), target.getAbsolutePath()); }
                    catch (ErrnoException ignored) {}
                } else if (entry.isLink()) {
                    File linked = new File(destination, entry.getLinkName()).getCanonicalFile();
                    File parent = target.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try { Os.link(linked.getAbsolutePath(), target.getAbsolutePath()); }
                    catch (ErrnoException ignored) {}
                } else {
                    File parent = target.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(target))) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = tar.read(buffer)) >= 0) outputStream.write(buffer, 0, read);
                    }
                }
                try { Os.chmod(target.getAbsolutePath(), entry.getMode()); }
                catch (Exception ignored) {}
                processed++;
                if (processed % 200 == 0) listener.onProgress("runtime-extract", 45 + (int) Math.min(20, processed / 250), "Extracting Linux runtime…");
            }
        }
    }

    private void writeRuntimeConfiguration() throws Exception {
        File etc = new File(rootfsDir, "etc");
        etc.mkdirs();
        FileIo.writeUtf8(new File(etc, "resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        FileIo.writeUtf8(new File(etc, "hosts"), "127.0.0.1 localhost\n::1 localhost\n");
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unit = -1;
        do { value /= 1024.0; unit++; } while (value >= 1024 && unit < units.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit]);
    }

    private static String tail(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete() && file.exists()) throw new IOException("Unable to delete " + file);
    }
}
