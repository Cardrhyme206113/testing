#!/usr/bin/env python3
"""Stage original 4J C++ units privately without copying source to public Git."""
import argparse,hashlib,json,pathlib,re
p=argparse.ArgumentParser()
p.add_argument('--source-root',required=True,type=pathlib.Path)
p.add_argument('--destination',required=True,type=pathlib.Path)
a=p.parse_args()
root=a.source_root/'Minecraft.World'
names=['Random','ImprovedNoise','PerlinNoise','Mth','Synth']
a.destination.mkdir(parents=True,exist_ok=True)
checks={}
for n in names:
  for ext in ['.cpp','.h']:
    src=root/(n+ext)
    raw=src.read_bytes()
    try:s=raw.decode('utf-8-sig')
    except UnicodeDecodeError:s=raw.decode('cp1252')
    if ext=='.cpp':
      s,count=re.subn(r'#include\s+"stdafx\.h"','#include "android_world_prelude.h"',s,count=1)
      if count!=1:raise SystemExit(f"Missing original precompiled-header include: {src}")
    if n=='Random' and ext=='.cpp':
      s,count=re.subn(r'^#include\s+"System\.h"[^\n]*\n','',s,count=1,flags=re.MULTILINE)
      if count!=1:raise SystemExit('Original Random.cpp differs from expected revision')
    (a.destination/(n+ext)).write_text(s,encoding='utf-8')
    checks[n+ext]={'original_sha256':hashlib.sha256(raw).hexdigest(),
                   'staged_sha256':hashlib.sha256(s.encode()).hexdigest()}
(a.destination/'android_world_prelude.h').write_bytes((pathlib.Path(__file__).parent/'android_world_prelude.h').read_bytes())
(a.destination/'STAGING-MANIFEST.json').write_text(json.dumps(checks,indent=2)+'\n')
print('Staged',len(checks),'original source/header files from',root)
print('Algorithm bodies untouched. Source directory must remain private.')
