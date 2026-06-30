from pathlib import Path

path = Path('app/src/main/java/com/example/blockhost/LinuxRuntimeManager.java')
text = path.read_text(encoding='utf-8')

call_needle = '''            FileIo.writeUtf8(mavenRc, "export JAVA_HOME=/usr/lib/jvm/java-21-openjdk\\n");
            chmod(mavenRc, 0644);
'''
call_replacement = call_needle + '''
            installDirectMavenLauncher();
'''
if 'installDirectMavenLauncher();' not in text:
    if call_needle not in text:
        raise SystemExit('Maven repair insertion point not found')
    text = text.replace(call_needle, call_replacement, 1)

method_needle = '''    private static void ensureBusyboxApplet(File busybox, File target) {
'''
method = '''    private void installDirectMavenLauncher() throws Exception {
        File launcher = new File(rootfsDir, "usr/bin/mvn");
        Files.deleteIfExists(launcher.toPath());
        File parent = launcher.getParentFile();
        if (parent != null) parent.mkdirs();

        String script = "#!/bin/bash\\n"
                + "set -e\\n"
                + "JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}\\n"
                + "MAVEN_HOME=/usr/share/java/maven-3\\n"
                + "set -- ${MAVEN_HOME}/boot/plexus-classworlds-*.jar \\\"$@\\\"\\n"
                + "BOOT_JAR=$1\\n"
                + "shift\\n"
                + "exec \\\"${JAVA_HOME}/bin/java\\\" \\\\\n"
                + "  -Dmaven.home=\\\"${MAVEN_HOME}\\\" \\\\\n"
                + "  -Dclassworlds.conf=\\\"${MAVEN_HOME}/bin/m2.conf\\\" \\\\\n"
                + "  -Dmaven.multiModuleProjectDirectory=\\\"${MAVEN_PROJECTBASEDIR:-$PWD}\\\" \\\\\n"
                + "  -classpath \\\"${BOOT_JAR}\\\" \\\\\n"
                + "  org.codehaus.plexus.classworlds.launcher.Launcher \\\"$@\\\"\\n";
        FileIo.writeUtf8(launcher, script);
        chmod(launcher, 0755);
    }

'''
if 'private void installDirectMavenLauncher()' not in text:
    if method_needle not in text:
        raise SystemExit('Maven launcher method insertion point not found')
    text = text.replace(method_needle, method + method_needle, 1)

path.write_text(text, encoding='utf-8')
print('Installed direct Maven Java launcher without symlink-resolution cd calls')
