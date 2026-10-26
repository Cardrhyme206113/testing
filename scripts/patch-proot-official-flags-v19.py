from pathlib import Path

path = Path('app/src/main/java/com/example/blockhost/LinuxRuntimeManager.java')
text = path.read_text(encoding='utf-8')

# PRoot's seccomp accelerator is the normal/default path. Forcing the old
# ptrace-only path produced ENOSYS ("Function not implemented") for modern
# guest filesystem syscalls on this Android device.
text = text.replace('        env.put("PROOT_NO_SECCOMP", "1");\n', '')

# A non-login shell is enough because BlockHost supplies the complete guest
# environment explicitly. Avoid Bash profile/cwd probing during startup.
text = text.replace('        command.add("-lc");', '        command.add("-c");')

pwd_needle = '        command.add("TMPDIR=/tmp");\n'
if 'command.add("PWD=" + (serverDir != null ? "/server" : "/"));' not in text:
    if pwd_needle not in text:
        raise SystemExit('TMPDIR environment insertion point not found')
    text = text.replace(
        pwd_needle,
        pwd_needle
        + '        command.add("PWD=" + (serverDir != null ? "/server" : "/"));\n'
        + '        command.add("GIT_CONFIG_NOSYSTEM=1");\n'
        + '        command.add("GIT_CONFIG_GLOBAL=/dev/null");\n',
        1,
    )

# Replace the minimal hand-written PRoot invocation with the same important
# extensions/binds used by Termux proot-distro for normal Linux guests.
start = text.index('        List<String> args = new ArrayList<>();', text.index('private List<String> baseProotCommand'))
end = text.index('        return args;', start)
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
text = text[:start] + new_args + text[end:]

# Repair/normalise the filesystem after every fresh extraction, not only when
# retrying an existing failed runtime.
marker_needle = '        FileIo.writeUtf8(readyMarker, "ready\\n");\n'
repair_call = '        listener.onProgress("runtime-repair", 99, "Applying Android PRoot compatibility fixes");\n        repairRuntimeCompatibility();\n\n'
if repair_call not in text:
    if marker_needle not in text:
        raise SystemExit('Ready marker insertion point not found')
    text = text.replace(marker_needle, repair_call + marker_needle, 1)

# Add concrete fixes for the exact paths reported by the phone. Android PRoot
# was returning ENOSYS while resolving libz's relative symlink and Git's absent
# config hierarchy. A regular libz file and pre-created config paths avoid both.
repair_needle = '''            installDirectMavenLauncher();
'''
extra_repairs = '''            installDirectMavenLauncher();

            File libzReal = new File(rootfsDir, "usr/lib/libz.so.1.3.2");
            File libzCompat = new File(rootfsDir, "usr/lib/libz.so.1");
            if (libzReal.isFile()) {
                Files.deleteIfExists(libzCompat.toPath());
                Files.copy(libzReal.toPath(), libzCompat.toPath(), StandardCopyOption.REPLACE_EXISTING);
                chmod(libzCompat, 0755);
            }

            File gitConfigDir = new File(rootfsDir, "root/.config/git");
            gitConfigDir.mkdirs();
            chmod(new File(rootfsDir, "root"), 0755);
            chmod(new File(rootfsDir, "root/.config"), 0755);
            chmod(gitConfigDir, 0755);
            File gitConfig = new File(gitConfigDir, "config");
            if (!gitConfig.isFile()) FileIo.writeUtf8(gitConfig, "");
            chmod(gitConfig, 0644);
'''
if extra_repairs not in text:
    if repair_needle not in text:
        raise SystemExit('Direct Maven repair call not found')
    text = text.replace(repair_needle, extra_repairs, 1)

path.write_text(text, encoding='utf-8')
print('Applied official proot-distro flags, seccomp default, cwd fix, and concrete runtime repairs')
