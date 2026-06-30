#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.runtime-build"
ROOTFS="$WORK/rootfs"
ASSET="$ROOT/app/src/main/assets/runtime-aarch64.tar.gz"
ALPINE_VERSION="v3.21"
MIRROR="https://dl-cdn.alpinelinux.org/alpine"

rm -rf "$WORK"
mkdir -p "$ROOTFS" "$(dirname "$ASSET")"
rm -f "$ASSET"

python3 - "$WORK" "$MIRROR" "$ALPINE_VERSION" <<'PY'
import io
import pathlib
import sys
import tarfile
import urllib.request

work = pathlib.Path(sys.argv[1])
mirror = sys.argv[2]
version = sys.argv[3]
index_url = f"{mirror}/{version}/main/x86_64/APKINDEX.tar.gz"
raw = urllib.request.urlopen(index_url, timeout=60).read()
with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
    text = archive.extractfile("APKINDEX").read().decode()
package_version = None
for block in text.split("\n\n"):
    fields = dict(line.split(":", 1) for line in block.splitlines() if ":" in line)
    if fields.get("P") == "apk-tools-static":
        package_version = fields["V"]
        break
if not package_version:
    raise SystemExit("apk-tools-static was not found")
url = f"{mirror}/{version}/main/x86_64/apk-tools-static-{package_version}.apk"
(work / "apk-tools-static.apk").write_bytes(urllib.request.urlopen(url, timeout=60).read())
PY

tar -xzf "$WORK/apk-tools-static.apk" -C "$WORK"
APK="$WORK/sbin/apk.static"
chmod 755 "$APK"

set +e
"$APK" \
  --root "$ROOTFS" \
  --arch aarch64 \
  --initdb \
  --no-cache \
  --no-scripts \
  --allow-untrusted \
  --repository "$MIRROR/$ALPINE_VERSION/main" \
  --repository "$MIRROR/$ALPINE_VERSION/community" \
  add \
    alpine-base \
    bash \
    ca-certificates \
    curl \
    git \
    maven \
    openjdk17-jdk \
    openjdk21-jdk \
    tar \
    unzip \
    zip
APK_STATUS=$?
set -e
echo "apk.static exited with $APK_STATUS; validating installed files instead of package database status"

mkdir -p "$ROOTFS/etc/ssl/certs" "$ROOTFS/root" "$ROOTFS/tmp" "$ROOTFS/var/tmp" "$ROOTFS/server"
printf '%s\n' "$MIRROR/$ALPINE_VERSION/main" "$MIRROR/$ALPINE_VERSION/community" > "$ROOTFS/etc/apk/repositories"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf"
printf '127.0.0.1 localhost\n::1 localhost\n' > "$ROOTFS/etc/hosts"
cp /etc/ssl/certs/ca-certificates.crt "$ROOTFS/etc/ssl/certs/ca-certificates.crt"
chmod 1777 "$ROOTFS/tmp" "$ROOTFS/var/tmp"
chmod 644 "$ROOTFS/etc/resolv.conf" "$ROOTFS/etc/hosts" "$ROOTFS/etc/ssl/certs/ca-certificates.crt"

ln -sfn /usr/lib/jvm/java-21-openjdk/bin/java "$ROOTFS/usr/bin/java"
ln -sfn /usr/lib/jvm/java-21-openjdk/bin/javac "$ROOTFS/usr/bin/javac"
ln -sfn /usr/lib/jvm/java-21-openjdk/bin/jar "$ROOTFS/usr/bin/jar"

file "$ROOTFS/usr/lib/jvm/java-21-openjdk/bin/java" | grep -Eq 'ARM aarch64|ARM64'
test -x "$ROOTFS/bin/bash"
test -x "$ROOTFS/usr/bin/git"
test -L "$ROOTFS/usr/bin/mvn"
test -x "$ROOTFS/usr/share/java/maven-3/bin/mvn"
test -x "$ROOTFS/usr/lib/jvm/java-17-openjdk/bin/java"
test -x "$ROOTFS/usr/lib/jvm/java-21-openjdk/bin/java"

rm -rf "$ROOTFS/var/cache/apk"/* "$ROOTFS/tmp"/* "$ROOTFS/root/.cache"
tar --numeric-owner --owner=0 --group=0 --format=posix -C "$ROOTFS" -czf "$ASSET" .
gzip -t "$ASSET"
test "$(stat -c%s "$ASSET")" -gt 50000000
ls -lh "$ASSET"
