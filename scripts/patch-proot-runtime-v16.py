from pathlib import Path

path = Path('app/src/main/java/com/example/blockhost/LinuxRuntimeManager.java')
text = path.read_text(encoding='utf-8')

if 'private String lastValidationOutput' not in text:
    text = text.replace(
        '    private final File archiveFile;\n',
        '    private final File archiveFile;\n    private String lastValidationOutput = "";\n',
        1,
    )

old = '''        ensureStorageAvailable();
        listener.onProgress("runtime-check", 1, "Checking runtime package");
'''
new = '''        if (rootfsDir.isDirectory()) {
            listener.onProgress("runtime-repair", 1, "Repairing extracted Java runtime");
            repairRuntimeCompatibility();
            if (validateRuntime()) {
                FileIo.writeUtf8(readyMarker, "ready\\n");
                archiveFile.delete();
                removeOldRuntime(new File(runtimeDir, "alpine"));
                removeOldRuntime(new File(runtimeDir, "alpine-bundled-v1"));
                deleteLegacyMarkers();
                listener.onProgress("runtime", 100, "Java runtime ready");
                return;
            }
        }

        ensureStorageAvailable();
        listener.onProgress("runtime-check", 1, "Checking runtime package");
'''
if old in text:
    text = text.replace(old, new, 1)

text = text.replace(
    '            throw new IOException("Downloaded Java runtime failed validation");',
    '            throw new IOException("Downloaded Java runtime failed validation:\\n" + lastValidationOutput);',
    1,
)

text = text.replace(
    '        command.add("/usr/bin/env");\n        command.add("-i");',
    '        command.add("/bin/busybox");\n        command.add("env");\n        command.add("-i");',
    1,
)

if 'command.add("JAVA_HOME=/usr/lib/jvm/java-21-openjdk");' not in text:
    text = text.replace(
        '        command.add("SHELL=/bin/bash");\n',
        '        command.add("SHELL=/bin/bash");\n        command.add("JAVA_HOME=/usr/lib/jvm/java-21-openjdk");\n        command.add("TMPDIR=/tmp");\n',
        1,
    )

if 'env.put("PROOT_NO_SECCOMP", "1");' not in text:
    text = text.replace(
        '        env.put("PROOT_LOADER", new File(nativeDir, "libproot_loader.so").getAbsolutePath());\n',
        '        env.put("PROOT_LOADER", new File(nativeDir, "libproot_loader.so").getAbsolutePath());\n        env.put("PROOT_NO_SECCOMP", "1");\n',
        1,
    )

text = text.replace('args.add("-b"); args.add("/dev");', 'args.add("-b"); args.add("/dev:/dev");')
text = text.replace('args.add("-b"); args.add("/proc");', 'args.add("-b"); args.add("/proc:/proc");')
text = text.replace('args.add("-b"); args.add("/sys");', 'args.add("-b"); args.add("/sys:/sys");')

start = text.index('    private boolean validateRuntime() {')
end = text.index('    private List<String> baseProotCommand', start)
replacement = '''    private void repairRuntimeCompatibility() {
        try {
            File busybox = new File(rootfsDir, "bin/busybox");
            if (!busybox.isFile()) return;
            String[] applets = {
                    "usr/bin/env", "bin/uname", "bin/ls", "usr/bin/expr",
                    "usr/bin/dirname", "usr/bin/basename", "bin/readlink",
                    "bin/pwd", "bin/cat", "bin/echo", "bin/mkdir", "bin/rm",
                    "bin/cp", "bin/mv", "bin/sed", "bin/grep", "usr/bin/which",
                    "usr/bin/test", "usr/bin/head", "usr/bin/tail", "usr/bin/cut",
                    "usr/bin/tr", "usr/bin/sort", "usr/bin/xargs", "usr/bin/awk"
            };
            for (String relative : applets) ensureBusyboxApplet(busybox, new File(rootfsDir, relative));

            File jvmDir = new File(rootfsDir, "usr/lib/jvm");
            jvmDir.mkdirs();
            Path defaultJvm = new File(jvmDir, "default-jvm").toPath();
            Files.deleteIfExists(defaultJvm);
            Os.symlink("java-21-openjdk", defaultJvm.toString());

            File etc = new File(rootfsDir, "etc");
            etc.mkdirs();
            File mavenRc = new File(etc, "mavenrc");
            FileIo.writeUtf8(mavenRc, "export JAVA_HOME=/usr/lib/jvm/java-21-openjdk\\n");
            chmod(mavenRc, 0644);
        } catch (Exception ignored) {}
    }

    private static void ensureBusyboxApplet(File busybox, File target) {
        try {
            if (target.isFile() && !Files.isSymbolicLink(target.toPath())) return;
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.deleteIfExists(target.toPath());
            try {
                Os.link(busybox.getAbsolutePath(), target.getAbsolutePath());
            } catch (Exception linkError) {
                Files.copy(busybox.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                chmod(target, 0755);
            }
        } catch (Exception ignored) {}
    }

    private boolean validateRuntime() {
        try {
            String diagnostics = "set +e; fail=0; "
                    + "echo '[validate] dev'; /bin/busybox ls -l /dev/null /dev/urandom 2>&1 || fail=1; "
                    + "echo '[validate] bash'; /bin/bash --version 2>&1 || fail=1; "
                    + "echo '[validate] java21'; /usr/bin/java -version 2>&1 || fail=1; "
                    + "echo '[validate] java17'; /usr/lib/jvm/java-17-openjdk/bin/java -version 2>&1 || fail=1; "
                    + "echo '[validate] git'; /usr/bin/git --version 2>&1 || fail=1; "
                    + "echo '[validate] maven'; /usr/bin/mvn -version 2>&1 || fail=1; "
                    + "echo '[validate] result='$fail; exit $fail";
            Process process = startShell(null, diagnostics);
            String output = ProcessIo.consume(process, null);
            int exit = process.waitFor();
            lastValidationOutput = output.trim();
            if (lastValidationOutput.length() > 6000) {
                lastValidationOutput = lastValidationOutput.substring(lastValidationOutput.length() - 6000);
            }
            return exit == 0;
        } catch (Exception error) {
            lastValidationOutput = error.getClass().getSimpleName() + ": " + error.getMessage();
            return false;
        }
    }

'''
text = text[:start] + replacement + text[end:]

path.write_text(text, encoding='utf-8')
print('Patched PRoot runtime validation and compatibility repair')
