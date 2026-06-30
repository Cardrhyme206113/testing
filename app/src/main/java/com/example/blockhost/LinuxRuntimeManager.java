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
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** App-private Alpine/Java runtime used by the Minecraft server backend. */
public final class LinuxRuntimeManager {
    public interface ProgressListener {
        void onProgress(String phase, int percent, String message);
    }

    private static final String ALPINE_RELEASES_URL =
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml";
    private static final String ALPINE_RELEASES_BASE =
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/";

    private final Context context;
    private final File runtimeDir;
    private final File rootfsDir;
    private final File readyMarker;

    public LinuxRuntimeManager(Context context, ServerRepository repository) {
        this.context = context.getApplicationContext();
        runtimeDir = repository.getRuntimeDir();
        rootfsDir = new File(runtimeDir, "alpine");
        readyMarker = new File(runtimeDir, ".ready-v4");
    }

    public boolean isReady() {
        return readyMarker.isFile()
                && existsNoFollow(new File(rootfsDir, "usr/bin/java"))
                && existsNoFollow(new File(rootfsDir, "usr/bin/git"))
                && existsNoFollow(new File(rootfsDir, "usr/bin/mvn"));
    }

    public synchronized void ensureReady(ProgressListener listener) throws Exception {
        if (isReady()) {
            listener.onProgress("runtime", 100, "Linux Java runtime ready");
            return;
        }

        verifyArchitecture();
        runtimeDir.mkdirs();

        File guestShell = new File(rootfsDir, "bin/sh");
        File busybox = new File(rootfsDir, "bin/busybox");
        if (!existsNoFollow(guestShell) && !busybox.isFile()) {
            listener.onProgress("runtime-download", 1, "Finding the current Alpine ARM64 image");
            String archiveName = discoverAlpineArchive();
            File archive = new File(runtimeDir, archiveName);
            download(ALPINE_RELEASES_BASE + archiveName, archive, "runtime-download", listener);

            listener.onProgress("runtime-extract", 45, "Extracting Linux runtime");
            deleteRecursively(rootfsDir);
            rootfsDir.mkdirs();
            extractTarGz(archive, rootfsDir, listener);
            archive.delete();
        }

        /*
         * apk-tools downloads repository indexes as the unprivileged _apk user.
         * The previous build changed the entire rootfs to 0700/0600, which made
         * CA certificates, repositories and cache directories inaccessible to _apk.
         */
        repairPublicPermissions(rootfsDir);
        prepareWritableApkPaths();
        writeRuntimeConfiguration();

        if (validateRuntime()) {
            FileIo.writeUtf8(readyMarker, "ready\n");
            listener.onProgress("runtime", 100, "Existing Java runtime repaired and ready");
            return;
        }

        listener.onProgress("runtime-packages", 70, "Installing Java, Git and build tools (first install only)");
        String installCommand =
                "mkdir -p /tmp /var/tmp /var/cache/apk /lib/apk/db /var/lib/apk; "
                        + "chmod 755 / /bin /sbin /etc /etc/apk /lib /lib/apk /usr /var /var/cache /var/lib 2>/dev/null || true; "
                        + "chmod 1777 /tmp /var/tmp /var/cache/apk 2>/dev/null || true; "
                        + "chmod 777 /lib/apk/db /var/lib/apk 2>/dev/null || true; "
                        + "chmod 644 /etc/apk/repositories /etc/resolv.conf /etc/hosts 2>/dev/null || true; "
                        + "apk --no-progress update && "
                        + "(apk --no-progress add --no-cache bash curl ca-certificates git maven openjdk21-jdk openjdk17-jdk "
                        + "|| apk --no-progress add --no-cache bash curl ca-certificates git maven openjdk21-jdk)";

        Process process = startShell(null, installCommand);
        String output = ProcessIo.consume(process,
                line -> listener.onProgress("runtime-packages", 80, line));
        int exit = process.waitFor();

        repairPublicPermissions(rootfsDir);
        prepareWritableApkPaths();
        boolean toolsWork = validateRuntime();
        if (!toolsWork) {
            throw new IOException("Runtime package installation failed (exit " + exit + "): " + tail(output, 1600));
        }

        if (exit != 0) {
            listener.onProgress("runtime-packages", 96,
                    "Packages are usable; ignoring APK database finalization warning");
        }
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
        command.add("SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt");

        String shell = existsNoFollow(new File(rootfsDir, "bin/bash")) ? "/bin/bash" : "/bin/sh";
        command.add("SHELL=" + shell);
        command.add(shell);
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
        File java17 = new File(rootfsDir, "usr/lib/jvm/java-17-openjdk/bin/java");
        if (existsNoFollow(java17)) return "/usr/lib/jvm/java-17-openjdk/bin/java";
        return "/usr/bin/java";
    }

    private boolean validateRuntime() {
        try {
            if (!existsNoFollow(new File(rootfsDir, "usr/bin/java"))
                    || !existsNoFollow(new File(rootfsDir, "usr/bin/git"))
                    || !existsNoFollow(new File(rootfsDir, "usr/bin/mvn"))) {
                return false;
            }
            Process validation = startShell(null,
                    "java -version >/dev/null 2>&1 && git --version >/dev/null 2>&1 && mvn -version >/dev/null 2>&1");
            ProcessIo.consume(validation, null);
            return validation.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
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
        if (serverDir != null) {
            serverDir.mkdirs();
            args.add("-b"); args.add(serverDir.getAbsolutePath() + ":/server");
            args.add("-w"); args.add("/server");
        } else {
            args.add("-w"); args.add("/root");
        }
        return args;
    }

    private void prepareWritableApkPaths() {
        chmodPath(new File(rootfsDir, "tmp"), 01777, true);
        chmodPath(new File(rootfsDir, "var/tmp"), 01777, true);
        chmodPath(new File(rootfsDir, "var/cache/apk"), 01777, true);
        chmodPath(new File(rootfsDir, "lib/apk/db"), 0777, true);
        chmodPath(new File(rootfsDir, "var/lib/apk"), 0777, true);
        chmodPath(new File(rootfsDir, "etc/apk/repositories"), 0644, false);
        chmodPath(new File(rootfsDir, "etc/resolv.conf"), 0644, false);
        chmodPath(new File(rootfsDir, "etc/hosts"), 0644, false);
    }

    private static void chmodPath(File file, int mode, boolean directory) {
        try {
            if (!existsNoFollow(file)) {
                if (directory) file.mkdirs();
                else return;
            }
            if (!Files.isSymbolicLink(file.toPath())) Os.chmod(file.getAbsolutePath(), mode);
        } catch (Exception ignored) {}
    }

    private void verifyArchitecture() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return;
        }
        throw new IllegalStateException("This experimental backend currently supports ARM64 Android devices only");
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
        int lastPercent = -1;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(partial))) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                done += read;
                int percent = total > 0 ? (int) Math.min(44, 2 + done * 42 / total) : 10;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    listener.onProgress(phase, percent,
                            "Downloading Linux runtime: " + humanBytes(done)
                                    + (total > 0 ? " / " + humanBytes(total) : ""));
                }
            }
        } finally {
            connection.disconnect();
        }
        if (!partial.renameTo(output)) {
            Files.move(partial.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void extractTarGz(File archive, File destination, ProgressListener listener) throws Exception {
        long processed = 0;
        File canonicalDestination = destination.getCanonicalFile();
        String destinationPath = canonicalDestination.getPath();
        try (InputStream fileInput = new BufferedInputStream(new FileInputStream(archive));
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(fileInput);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String originalName = entry.getName();
                String name = normalizeArchiveName(originalName);
                if (name.isEmpty() || ".".equals(name)) {
                    processed++;
                    continue;
                }
                File target = resolveArchivePath(canonicalDestination, destinationPath, name, originalName);
                if (entry.isDirectory()) {
                    target.mkdirs();
                    chmodPath(target, 0755, true);
                } else if (entry.isSymbolicLink()) {
                    File parent = target.getParentFile();
                    if (parent != null) { parent.mkdirs(); chmodPath(parent, 0755, true); }
                    try { Os.symlink(entry.getLinkName(), target.getAbsolutePath()); }
                    catch (ErrnoException ignored) {}
                } else if (entry.isLink()) {
                    String linkName = normalizeArchiveName(entry.getLinkName());
                    File linked = resolveArchivePath(canonicalDestination, destinationPath, linkName, entry.getLinkName());
                    File parent = target.getParentFile();
                    if (parent != null) { parent.mkdirs(); chmodPath(parent, 0755, true); }
                    try { Os.link(linked.getAbsolutePath(), target.getAbsolutePath()); }
                    catch (ErrnoException ignored) {}
                    chmodPath(target, (entry.getMode() & 0111) != 0 ? 0755 : 0644, false);
                } else {
                    File parent = target.getParentFile();
                    if (parent != null) { parent.mkdirs(); chmodPath(parent, 0755, true); }
                    try (BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(target))) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = tar.read(buffer)) >= 0) outputStream.write(buffer, 0, read);
                    }
                    chmodPath(target, (entry.getMode() & 0111) != 0 ? 0755 : 0644, false);
                }
                processed++;
                if (processed % 200 == 0) {
                    listener.onProgress("runtime-extract",
                            45 + (int) Math.min(20, processed / 250),
                            "Extracting Linux runtime…");
                }
            }
        }
    }

    private static String normalizeArchiveName(String name) {
        String normalized = name == null ? "" : name.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    private static File resolveArchivePath(File destination, String destinationPath,
                                           String normalizedName, String displayName) throws Exception {
        if (normalizedName.startsWith("/")
                || "..".equals(normalizedName)
                || normalizedName.startsWith("../")
                || normalizedName.contains("/../")) {
            throw new SecurityException("Unsafe archive path: " + displayName);
        }
        File target = new File(destination, normalizedName).getCanonicalFile();
        String targetPath = target.getPath();
        if (!targetPath.equals(destinationPath)
                && !targetPath.startsWith(destinationPath + File.separator)) {
            throw new SecurityException("Unsafe archive path: " + displayName);
        }
        return target;
    }

    private void writeRuntimeConfiguration() throws Exception {
        File etc = new File(rootfsDir, "etc");
        etc.mkdirs();
        chmodPath(etc, 0755, true);
        FileIo.writeUtf8(new File(etc, "resolv.conf"),
                "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        FileIo.writeUtf8(new File(etc, "hosts"),
                "127.0.0.1 localhost\n::1 localhost\n");
        chmodPath(new File(etc, "resolv.conf"), 0644, false);
        chmodPath(new File(etc, "hosts"), 0644, false);
    }

    private static boolean existsNoFollow(File file) {
        try {
            return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void repairPublicPermissions(File file) {
        if (file == null || !existsNoFollow(file)) return;
        try {
            if (Files.isSymbolicLink(file.toPath())) return;
        } catch (Exception ignored) {}

        if (file.isDirectory()) {
            chmodPath(file, 0755, true);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) repairPublicPermissions(child);
            }
            chmodPath(file, 0755, true);
        } else {
            chmodPath(file, file.canExecute() ? 0755 : 0644, false);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !existsNoFollow(file)) return;
        File parent = file.getParentFile();
        if (parent != null && existsNoFollow(parent)) chmodPath(parent, 0755, true);

        boolean symlink = false;
        try { symlink = Files.isSymbolicLink(file.toPath()); }
        catch (Exception ignored) {}

        if (!symlink && file.isDirectory()) {
            chmodPath(file, 0755, true);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        } else if (!symlink) {
            chmodPath(file, file.canExecute() ? 0755 : 0644, false);
        }

        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception first) {
            if (!file.delete() && existsNoFollow(file)) {
                throw new IOException("Unable to delete " + file, first);
            }
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unit = -1;
        do { value /= 1024.0; unit++; }
        while (value >= 1024 && unit < units.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit]);
    }

    private static String tail(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(text.length() - max);
    }
}
