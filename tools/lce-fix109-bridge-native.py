#!/usr/bin/env python3
"""Route patched native Flash movie bytes into Ruffle instead of archived originals.
No HTML or canvas replacement is created. Original C++ scene model stays intact.
"""
from pathlib import Path
import sys

path=Path(sys.argv[1]);source=path.read_text(encoding='utf-8')
old='''    const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    try {
      const loadOptions = dataLoadOptions(data, filename);
'''
new='''    let data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    try {
      const nativeFlashName = basename(filename);
      // The WebIggy C++ bridge supplies scenes from MediaWindows64.arc.
      // Fix109 replaces only two decorative, broken scale9 panels in these
      // originals. Ruffle must load the corrected SWF bytes, not the old ARC
      // payload; Common/UI still controls the same scene and input objects.
      if (nativeFlashName === "Controls720.swf" ||
          nativeFlashName === "CreateWorldMenu720.swf") {
        const assetUrl = new URL(nativeFlashName, RUNTIME_BASE_URL);
        const response = await fetch(assetUrl, {cache: "no-store"});
        if (!response.ok) throw new Error("Missing native Flash UI asset: " + nativeFlashName);
        data = new Uint8Array(await response.arrayBuffer());
        globalThis.__lceFix109SwfLoads ||= [];
        globalThis.__lceFix109SwfLoads.push({name:nativeFlashName,bytes:data.byteLength});
        console.log("[LCE][Fix109] corrected original SWF",nativeFlashName,data.byteLength);
      }
      const loadOptions = dataLoadOptions(data, filename);
'''
if new in source:
 print('Already patched')
else:
 assert source.count(old)==1,('WebIggy createRufflePlayer anchor missing',source.count(old))
 path.write_text(source.replace(old,new,1),encoding='utf-8')
print('LCE_FIX109_NATIVE_SWF_REPLACEMENT_READY')
