from pathlib import Path

path = Path('app/src/main/java/com/example/blockhost/LinuxRuntimeManager.java')
text = path.read_text(encoding='utf-8')

# Repair every freshly activated runtime before its first validation. This also
# replaces Alpine's stock Maven launcher with the direct Java launcher.
needle = '        FileIo.writeUtf8(readyMarker, "ready\\n");\n'
replacement = ('        listener.onProgress("runtime-repair", 99, '
               '"Applying Android PRoot compatibility fixes");\n'
               '        repairRuntimeCompatibility();\n'
               '        FileIo.writeUtf8(readyMarker, "ready\\n");\n')
if replacement not in text:
    if needle not in text:
        raise SystemExit('ready marker insertion point not found')
    text = text.replace(needle, replacement, 1)

# Start PRoot from a real host directory. A stale/nonexistent host cwd causes
# every guest getcwd() to fail before the guest command even starts.
needle = '        ProcessBuilder builder = new ProcessBuilder(command);\n        builder.redirectErrorStream(true);\n'
replacement = ('        ProcessBuilder builder = new ProcessBuilder(command);\n'
               '        builder.directory(serverDir != null ? serverDir : rootfsDir);\n'
               '        builder.redirectErrorStream(true);\n')
if 'builder.directory(serverDir != null ? serverDir : rootfsDir);' not in text:
    if needle not in text:
        raise SystemExit('ProcessBuilder insertion point not found')
    text = text.replace(needle, replacement, 1)

# Use PRoot's normal seccomp accelerator. The forced ptrace-only fallback was
# returning ENOSYS ("Function not implemented") for modern filesystem calls.
text = text.replace('        env.put("PROOT_NO_SECCOMP", "1");\n', '')

# BlockHost provides the complete environment itself, so use a non-login shell
# and avoid Bash's login/profile cwd probes.
text = text.replace('        command.add("-lc");', '        command.add("-c");')

vars_needle = '        command.add("TMPDIR=/tmp");\n'
vars_add = (vars_needle
            + '        command.add("PWD=" + (serverDir != null ? "/server" : "/"));\n'
            + '        command.add("GIT_CONFIG_NOSYSTEM=1");\n'
            + '        command.add("GIT_CONFIG_GLOBAL=/dev/null");\n'
            + '        command.add("GIT_CONFIG_SYSTEM=/dev/null");\n'
            + '        command.add("XDG_CONFIG_HOME=/tmp/git-config");\n')
if 'command.add("PWD=" + (serverDir != null ? "/server" : "/"));' not in text:
    if vars_needle not in text:
        raise SystemExit('guest env insertion point not found')
    text = text.replace(vars_needle, vars_add, 1)

# Match the important PRoot flags and Android binds used by Termux
# proot-distro. --link2symlink and -L are specifically relevant to the
# symlink/lstat failures visible in the phone log.
method_start = text.index('    private List<String> baseProotCommand')
args_start = text.index('        List<String> args = new ArrayList<>();', method_start)
args_end = text.index('        return args;', args_start)
new_args = '''        List<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("--kill-on-exit");
        args.add("--link2symlink");
        args.add("--sysvipc");
        args.add("-L");
        args.add("--root-id");
        args.add("--rootfs=" + rootfsDir.getAbsolutePath());
        args.add("--cwd=" + (serverDir != null ? "/server" : "/"));
        args.add("--bind=/dev");
        args.add("--bind=/proc");
        args.add("--bind=/sys");
        args.add("--bind=/dev/urandom:/dev/random");
        args.add("--bind=/proc/self/fd:/dev/fd");
        args.add("--bind=/proc/self/fd/0:/dev/stdin");
        args.add("--bind=/proc/self/fd/1:/dev/stdout");
        args.add("--bind=/proc/self/fd/2:/dev/stderr");

        File guestTmp = new File(rootfsDir, "tmp");
        guestTmp.mkdirs();
        chmod(guestTmp, 01777);
        args.add("--bind=" + guestTmp.getAbsolutePath() + ":/dev/shm");

        if (serverDir != null) {
            serverDir.mkdirs();
            makeServerTreeFullyWritable(serverDir);
            args.add("--bind=" + serverDir.getAbsolutePath() + ":/server");
        }
'''
text = text[:args_start] + new_args + text[args_end:]

# Replace the exact soname symlink Java failed to resolve. The file lives in
# /usr/lib, not /lib. Git config paths are also created even though the guest
# environment bypasses them, so tools cannot hit an absent-parent edge case.
call_needle = '            installDirectMavenLauncher();\n'
call_add = call_needle + '''            flattenLibrarySymlink(new File(rootfsDir, "usr/lib"), "libz.so.1");

            File gitConfigDir = new File(rootfsDir, "root/.config/git");
            gitConfigDir.mkdirs();
            chmod(new File(rootfsDir, "root"), 0755);
            chmod(new File(rootfsDir, "root/.config"), 0755);
            chmod(gitConfigDir, 0755);
            File gitConfig = new File(gitConfigDir, "config");
            if (!gitConfig.isFile()) FileIo.writeUtf8(gitConfig, "");
            chmod(gitConfig, 0644);
'''
if 'flattenLibrarySymlink(new File(rootfsDir, "usr/lib"), "libz.so.1");' not in text:
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
            }
            chmod(alias, 0755);
        } catch (Exception ignored) {}
    }

'''
if 'private static void flattenLibrarySymlink' not in text:
    if method_needle not in text:
        raise SystemExit('helper method insertion point not found')
    text = text.replace(method_needle, method + method_needle, 1)

path.write_text(text, encoding='utf-8')
print('Applied official PRoot flags, seccomp default, cwd repair, Maven launcher, libz and Git fixes')
