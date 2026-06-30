package com.example.blockhost;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Downloads a prebuilt ARM64 Alpine/JDK runtime through Android networking. */
public final class LinuxRuntimeManager {
    public interface ProgressListener {
        void onProgress(String phase, int percent, String message);
    }

    private static final String RUNTIME_URL =
            "https://github.com/Cardrhyme206113/hosting/releases/download/blockhost-runtime-v1/runtime-aarch64.tar.gz";
    private static final String RUNTIME_SHA_URL = RUNTIME_URL + ".sha256";
    private static final String RUNTIME_FOLDER = "alpine-download-v1";
    private static final String READY_MARKER = ".blockhost-runtime-ready-v1";
    private static final long MIN_FREE_BYTES = 1_100_000_000L;

    private final Context context;
    private final File runtimeDir;
    private final File rootfsDir;
    private final File readyMarker;
    private final File archiveFile;

    public LinuxRuntimeManager(Context context, ServerRepository repository) {
        this.context = context.getApplicationContext();
        runtimeDir = repository.getRuntimeDir();
        rootfsDir = new File(runtimeDir, RUNTIME_FOLDER);
        readyMarker = new File(rootfsDir, READY_MARKER);
        archiveFile = new File(runtimeDir, "runtime-aarch64-v1.tar.gz");
    }

    public boolean isReady() {
        return readyMarker.isFile()
                && existsNoFollow(new File(rootfsDir, "bin/bash"))
                && existsNoFollow(new File(rootfsDir, "usr/bin/java"))
                && existsNoFollow(new File(rootfsDir, "usr/bin/git"))
                && existsNoFollow(new File(rootfsDir, "usr/bin/mvn"));
    }

    public synchronized void ensureReady(ProgressListener listener) throws Exception {
        verifyArchitecture();
        runtimeDir.mkdirs();

        if (isReady() && validateRuntime()) {
            listener.onProgress("runtime", 100, "Java runtime ready");
            return;
        }

        ensureStorageAvailable();
        listener.onProgress("runtime-check", 1, "Checking runtime package");
        String expectedSha = downloadText(RUNTIME_SHA_URL).trim().split("\\s+")[0].toLowerCase(Locale.US);
        if (!expectedSha.matches("[0-9a-f]{64}")) {
            throw new IOException("Runtime checksum file is invalid");
        }

        if (!archiveFile.isFile() || !expectedSha.equals(sha256(archiveFile))) {
            archiveFile.delete();
            downloadRuntime(listener);
        }

        listener.onProgress("runtime-verify", 61, "Verifying downloaded runtime");
        String actualSha = sha256(archiveFile);
        if (!expectedSha.equals(actualSha)) {
            archiveFile.delete();
            throw new IOException("Runtime download checksum mismatch");
        }

        File staging = new File(runtimeDir, RUNTIME_FOLDER + ".partial");
        listener.onProgress("runtime-reset", 63, "Preparing Java runtime");
        deleteRecursively(staging);
        deleteRecursively(rootfsDir);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Unable to create runtime staging directory");
        }
        chmod(staging, 0700);

        listener.onProgress("runtime-extract", 65, "Extracting Java, Git and Maven");
        try (InputStream input = new BufferedInputStream(new FileInputStream(archiveFile));
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(input);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            extractTar(tar, staging, listener);
        } catch (Exception error) {
            deleteRecursively(staging);
            throw new IOException("Runtime extraction failed: " + error.getMessage(), error);
        }

        writeRuntimeConfiguration(staging);
        if (!staging.renameTo(rootfsDir)) {
            try {
                Files.move(staging.toPath(), rootfsDir.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception moveError) {
                deleteRecursively(staging);
                throw new IOException("Unable to activate downloaded runtime", moveError);
            }
        }

        FileIo.writeUtf8(readyMarker, "ready\n");
        if (!validateRuntime()) {
            readyMarker.delete();
            throw new IOException("Downloaded Java runtime failed validation");
        }

        archiveFile.delete();
        removeOldRuntime(new File(runtimeDir, "alpine"));
        removeOldRuntime(new File(runtimeDir, "alpine-bundled-v1"));
        deleteLegacyMarkers();
        listener.onProgress("runtime", 100, "Java runtime ready");
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
        File java17 = new File(rootfsDir, "usr/lib/jvm/java-17-openjdk/bin/java");
        if (existsNoFollow(java17)) return "/usr/lib/jvm/java-17-openjdk/bin/java";
        return "/usr/bin/java";
    }

    private void downloadRuntime(ProgressListener listener) throws Exception {
        File partial = new File(archiveFile.getParentFile(), archiveFile.getName() + ".part");
        partial.delete();

        HttpURLConnection connection = openConnection(RUNTIME_URL);
        long total = connection.getContentLengthLong();
        if (total <= 0) total = -1;

        byte[] buffer = new byte[256 * 1024];
        long done = 0;
        int lastPercent = -1;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                done += read;
                int percent = total > 0 ? 2 + (int) Math.min(58, done * 58 / total) : 20;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    listener.onProgress("runtime-download", percent,
                            "Downloading Java runtime: " + humanBytes(done)
                                    + (total > 0 ? " / " + humanBytes(total) : ""));
                }
            }
        } finally {
            connection.disconnect();
        }

        if (!partial.renameTo(archiveFile)) {
            Files.move(partial.toPath(), archiveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String downloadText(String url) throws Exception {
        HttpURLConnection connection = openConnection(url);
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            byte[] bytes = input.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "BlockHost-Android/0.3");
        connection.setRequestProperty("Accept", "application/octet-stream,*/*");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("Runtime download failed with HTTP " + code);
        }
        return connection;
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private void ensureStorageAvailable() throws IOException {
        StatFs statFs = new StatFs(runtimeDir.getAbsolutePath());
        long available = statFs.getAvailableBytes();
        if (available < MIN_FREE_BYTES) {
            throw new IOException("At least 1.1 GB of free storage is required for the Java runtime");
        }
    }

    private boolean validateRuntime() {
        try {
            Process process = startShell(null,
                    "java -version >/dev/null 2>&1"
                            + " && git --version >/dev/null 2>&1"
                            + " && mvn -version >/dev/null 2>&1"
                            + " && bash --version >/dev/null 2>&1");
            ProcessIo.consume(process, null);
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> baseProotCommand(File serverDir) throws IOException {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot_exec.so");
        if (!proot.isFile()) {
            throw new IOException("Bundled PRoot executable is missing for this CPU architecture");
        }
        if (!rootfsDir.isDirectory()) {
            throw new IOException("Linux runtime has not been installed");
        }

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
            makeServerTreeFullyWritable(serverDir);
            args.add("-b"); args.add(serverDir.getAbsolutePath() + ":/server");
            args.add("-w"); args.add("/server");
        } else {
            args.add("-w"); args.add("/root");
        }
        return args;
    }

    private void extractTar(TarArchiveInputStream tar, File destination,
                            ProgressListener listener) throws Exception {
        Path root = destination.toPath().toAbsolutePath().normalize();
        List<DirectoryMode> directoryModes = new ArrayList<>();
        List<PendingHardLink> pendingHardLinks = new ArrayList<>();
        int rootMode = 0755;
        long processed = 0;
        TarArchiveEntry entry;

        while ((entry = tar.getNextTarEntry()) != null) {
            String originalName = entry.getName();
            String name = normalizeArchiveName(originalName);
            if (name.isEmpty() || ".".equals(name)) {
                if (entry.isDirectory()) rootMode = entry.getMode();
                processed++;
                continue;
            }

            Path targetPath = resolveArchivePath(root, name, originalName);
            ensureNoSymlinkParents(root, targetPath.getParent());
            File target = targetPath.toFile();
            File parent = target.getParentFile();
            if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Unable to create runtime parent: " + name);
            }

            if (entry.isDirectory()) {
                if (Files.isSymbolicLink(targetPath)) {
                    throw new IOException("Runtime directory collides with symlink: " + name);
                }
                if (!target.mkdirs() && !target.isDirectory()) {
                    throw new IOException("Unable to create runtime directory: " + name);
                }
                chmod(target, 0700);
                directoryModes.add(new DirectoryMode(target, entry.getMode(), targetPath.getNameCount()));
            } else if (entry.isSymbolicLink()) {
                Files.deleteIfExists(targetPath);
                Os.symlink(entry.getLinkName(), target.getAbsolutePath());
            } else if (entry.isLink()) {
                String linkName = normalizeArchiveName(entry.getLinkName());
                Path sourcePath = resolveArchivePath(root, linkName, entry.getLinkName());
                if (Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    createHardLinkOrCopy(sourcePath, targetPath, entry.getMode());
                } else {
                    pendingHardLinks.add(new PendingHardLink(sourcePath, targetPath, entry.getMode()));
                }
            } else if (entry.isFile()) {
                Files.deleteIfExists(targetPath);
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    byte[] buffer = new byte[128 * 1024];
                    int read;
                    while ((read = tar.read(buffer)) >= 0) output.write(buffer, 0, read);
                }
                chmod(target, entry.getMode());
            }

            processed++;
            if (processed % 400 == 0) {
                int progress = 65 + Math.min(32, (int) (processed / 180));
                listener.onProgress("runtime-extract", progress,
                        "Extracting Java runtime… " + processed + " files");
            }
        }

        for (PendingHardLink hardLink : pendingHardLinks) {
            if (!Files.exists(hardLink.source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing runtime hard-link source: " + hardLink.source.getFileName());
            }
            ensureNoSymlinkParents(root, hardLink.target.getParent());
            createHardLinkOrCopy(hardLink.source, hardLink.target, hardLink.mode);
        }

        directoryModes.sort(Comparator.comparingInt((DirectoryMode mode) -> mode.depth).reversed());
        for (DirectoryMode mode : directoryModes) chmod(mode.directory, mode.mode);
        chmod(destination, rootMode == 0 ? 0755 : rootMode);
    }

    private static void createHardLinkOrCopy(Path source, Path target, int mode) throws IOException {
        Files.deleteIfExists(target);
        try {
            Os.link(source.toString(), target.toString());
        } catch (Exception linkError) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        chmod(target.toFile(), mode);
    }

    private static String normalizeArchiveName(String name) {
        String normalized = name == null ? "" : name.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Path resolveArchivePath(Path root, String normalizedName, String displayName) {
        if (normalizedName.indexOf('\0') >= 0
                || normalizedName.startsWith("/")
                || "..".equals(normalizedName)
                || normalizedName.startsWith("../")
                || normalizedName.contains("/../")) {
            throw new SecurityException("Unsafe archive path: " + displayName);
        }
        Path target = root.resolve(normalizedName).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("Unsafe archive path: " + displayName);
        }
        return target;
    }

    private static void ensureNoSymlinkParents(Path root, Path parent) throws IOException {
        if (parent == null) return;
        Path current = root;
        for (Path component : root.relativize(parent)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Archive entry traverses a symlinked directory: " + current.getFileName());
            }
        }
    }

    private void writeRuntimeConfiguration(File root) throws Exception {
        File etc = new File(root, "etc");
        if (!etc.mkdirs() && !etc.isDirectory()) {
            throw new IOException("Unable to create runtime /etc");
        }
        File resolv = new File(etc, "resolv.conf");
        File hosts = new File(etc, "hosts");
        FileIo.writeUtf8(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        FileIo.writeUtf8(hosts, "127.0.0.1 localhost\n::1 localhost\n");
        chmod(resolv, 0644);
        chmod(hosts, 0644);
    }

    private static void makeServerTreeFullyWritable(File file) {
        if (file == null || !existsNoFollow(file)) return;
        try {
            if (Files.isSymbolicLink(file.toPath())) return;
        } catch (Exception ignored) {}

        chmod(file, 0777);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) makeServerTreeFullyWritable(child);
            }
            chmod(file, 0777);
        }
    }

    private void verifyArchitecture() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return;
        }
        throw new IllegalStateException("This backend currently supports ARM64 Android devices only");
    }

    private void removeOldRuntime(File oldRuntime) {
        try {
            if (!oldRuntime.equals(rootfsDir)) deleteRecursively(oldRuntime);
        } catch (Exception ignored) {}
    }

    private void deleteLegacyMarkers() {
        File[] files = runtimeDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".ready") || name.startsWith(".clean-rootfs")) file.delete();
        }
    }

    private static boolean existsNoFollow(File file) {
        try {
            return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void chmod(File file, int mode) {
        try {
            if (!Files.isSymbolicLink(file.toPath())) {
                Os.chmod(file.getAbsolutePath(), mode & 07777);
            }
        } catch (Exception ignored) {}
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !existsNoFollow(file)) return;
        boolean symlink;
        try {
            symlink = Files.isSymbolicLink(file.toPath());
        } catch (Exception ignored) {
            symlink = false;
        }

        if (!symlink && file.isDirectory()) {
            chmod(file, 0777);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        } else if (!symlink) {
            chmod(file, 0777);
        }

        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception error) {
            if (!file.delete() && existsNoFollow(file)) {
                throw new IOException("Unable to delete " + file, error);
            }
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private static final class DirectoryMode {
        final File directory;
        final int mode;
        final int depth;

        DirectoryMode(File directory, int mode, int depth) {
            this.directory = directory;
            this.mode = mode;
            this.depth = depth;
        }
    }

    private static final class PendingHardLink {
        final Path source;
        final Path target;
        final int mode;

        PendingHardLink(Path source, Path target, int mode) {
            this.source = source;
            this.target = target;
            this.mode = mode;
        }
    }
}
