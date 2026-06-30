from pathlib import Path

path = Path('app/src/main/java/com/example/blockhost/LinuxRuntimeManager.java')
text = path.read_text(encoding='utf-8')

# Always repair the freshly activated runtime before the first validation.
needle = '        FileIo.writeUtf8(readyMarker, "ready\\n");\n'
replacement = '        repairRuntimeCompatibility();\n        FileIo.writeUtf8(readyMarker, "ready\\n");\n'
if replacement not in text:
    if needle not in text:
        raise SystemExit('ready marker insertion point not found')
    text = text.replace(needle, replacement, 1)

# Start PRoot from a valid host directory and use / instead of /root as the guest cwd.
needle = '        ProcessBuilder builder = new ProcessBuilder(command);\n        builder.redirectErrorStream(true);\n'
replacement = ('        ProcessBuilder builder = new ProcessBuilder(command);\n'
               '        builder.directory(serverDir != null ? serverDir : rootfsDir);\n'
               '        builder.redirectErrorStream(true);\n')
if 'builder.directory(serverDir != null ? serverDir : rootfsDir);' not in text:
    if needle not in text:
        raise SystemExit('ProcessBuilder insertion point not found')
    text = text.replace(needle, replacement, 1)

text = text.replace('            args.add("-w"); args.add("/root");',
                    '            args.add("-w"); args.add("/");')

# Prevent Git from probing config paths that currently trigger ENOSYS under embedded PRoot.
vars_needle = '        command.add("TMPDIR=/tmp");\n'
vars_add = (vars_needle
            + '        command.add("GIT_CONFIG_GLOBAL=/dev/null");\n'
            + '        command.add("GIT_CONFIG_SYSTEM=/dev/null");\n'
            + '        command.add("XDG_CONFIG_HOME=/tmp/git-config");\n')
if 'command.add("GIT_CONFIG_GLOBAL=/dev/null");' not in text:
    if vars_needle not in text:
        raise SystemExit('guest env insertion point not found')
    text = text.replace(vars_needle, vars_add, 1)

# Avoid login-shell cwd startup probes.
text = text.replace('        command.add("-lc");', '        command.add("-c");')

# Replace libz soname symlinks with hard links/copies to their real files.
call_needle = '            installDirectMavenLauncher();\n'
call_add = call_needle + '            flattenLibrarySymlink(new File(rootfsDir, "lib"), "libz.so.1");\n'
if 'flattenLibrarySymlink(new File(rootfsDir, "lib"), "libz.so.1");' not in text:
    if call_needle not in text:
        raise SystemExit('runtime repair call insertion point not found')
    text = text.replace(call_needle, call_add, 1)

method_needle = '    private static void ensureBusyboxApplet(File busybox, File target) {\n'
method = '''    private static void flattenLibrarySymlink(File directory, String soname) {
        try {
            File alias = new File(directory, soname);
            if (!Files.isSymbolicLink(alias.toPath())) return;
            Path link = Files.readSymbolicLink(alias.toPath());
            File target = link.isAbsolute()
                    ? new File(link.toString())
                    : new File(directory, link.toString());
            if (!target.isFile()) return;
            Files.delete(alias.toPath());
            try {
                Os.link(target.getAbsolutePath(), alias.getAbsolutePath());
            } catch (Exception hardLinkError) {
                Files.copy(target.toPath(), alias.toPath(), StandardCopyOption.REPLACE_EXISTING);
                chmod(alias, 0755);
            }
        } catch (Exception ignored) {}
    }

'''
if 'private static void flattenLibrarySymlink' not in text:
    if method_needle not in text:
        raise SystemExit('helper method insertion point not found')
    text = text.replace(method_needle, method + method_needle, 1)

path.write_text(text, encoding='utf-8')
print('Applied fresh-runtime repair, valid cwd, Git config bypass and libz flattening')
