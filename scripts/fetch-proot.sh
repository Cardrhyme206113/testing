#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
WORK="$ROOT/.proot-fetch"
rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT"
python3 - "$WORK" <<'PY'
import gzip, pathlib, subprocess, sys, urllib.request
work=pathlib.Path(sys.argv[1])
base='https://packages.termux.dev/apt/termux-main/'
raw=urllib.request.urlopen(base+'dists/stable/main/binary-aarch64/Packages.gz',timeout=60).read()
text=gzip.decompress(raw).decode()
records={}
for block in text.split('\n\n'):
 rec={}
 for line in block.splitlines():
  if ': ' in line:
   k,v=line.split(': ',1);rec[k]=v
 if 'Package' in rec: records[rec['Package']]=rec
for package in ['proot','libandroid-shmem','libtalloc']:
 rec=records.get(package)
 if not rec: raise SystemExit(f'Missing package {package}')
 deb=work/f'{package}.deb'
 urllib.request.urlretrieve(base+rec['Filename'],deb)
 dest=work/'root';dest.mkdir(exist_ok=True)
 subprocess.run(['dpkg-deb','-x',str(deb),str(dest)],check=True)
PY
PREFIX="$WORK/root/data/data/com.termux/files/usr"
PROOT="$(find "$PREFIX" -type f -path '*/bin/proot' | head -n1)"
LOADER="$(find "$PREFIX" -type f -path '*/libexec/proot/loader' | head -n1)"
LOADER32="$(find "$PREFIX" -type f -path '*/libexec/proot/loader-m32' | head -n1 || true)"
TALLOC="$(find "$PREFIX" -type f -name 'libtalloc.so*' | sort | tail -n1)"
SHMEM="$(find "$PREFIX" -type f -name 'libandroid-shmem.so*' | sort | tail -n1)"
[[ -n "$PROOT" && -n "$LOADER" && -n "$TALLOC" && -n "$SHMEM" ]]
cp "$PROOT" "$OUT/libproot_exec.so"
cp "$LOADER" "$OUT/libproot_loader.so"
if [[ -n "$LOADER32" ]]; then cp "$LOADER32" "$OUT/libproot_loader32.so"; fi
cp "$TALLOC" "$OUT/libtalloc.so"
cp "$TALLOC" "$OUT/libtalloc.so.2"
cp "$SHMEM" "$OUT/libandroid-shmem.so"
chmod 755 "$OUT"/*
file "$OUT"/*
