#!/usr/bin/env python3
"""Runtime bridge-only repair. Underlying native WASM binary is unchanged."""
from pathlib import Path
import sys
target=Path(sys.argv[1])
s=target.read_text(encoding='utf-8')
import re
anchor_match=re.search(r'\\b(?:const|var|let)\\s+RUNTIME_BASE_URL\\s*=',s)
assert anchor_match, 'RUNTIME_BASE_URL declaration not found'
anchor_start=anchor_match.start()
anchor_end=s.find(';', anchor_start)+1
assert anchor_end>anchor_start, 'RUNTIME_BASE_URL statement missing semicolon'
patch="""  // FIX116_MENU_CACHE: corrected original SWFs must not re-fetch on every opening.
  // Keep one in-flight promise per immutable resource and retry on network failure.
  const fixedMenuMovieRequests = new Map();
  function fixedMenuMovieBytes(name) {
    if (!fixedMenuMovieRequests.has(name)) {
      const url = new URL(name, RUNTIME_BASE_URL);
      const request = fetch(url, {cache: 'force-cache'}).then(async response => {
        if (!response.ok) throw new Error('Missing native Flash UI asset: ' + name);
        return new Uint8Array(await response.arrayBuffer());
      }).catch(error => {
        fixedMenuMovieRequests.delete(name);
        throw error;
      });
      fixedMenuMovieRequests.set(name, request);
    }
    return fixedMenuMovieRequests.get(name);
  }
  // Prefetch during idle time; never block the first rendered screen.
  const prewarmCorrectedMenus = () => {
    for (const name of ['Controls720.swf', 'CreateWorldMenu720.swf']) {
      fixedMenuMovieBytes(name).catch(() => {});
    }
  };
  if (document.readyState === 'complete') {
    if (typeof requestIdleCallback === 'function') requestIdleCallback(prewarmCorrectedMenus);
    else setTimeout(prewarmCorrectedMenus, 50);
  } else {
    window.addEventListener('load', () => {
      if (typeof requestIdleCallback === 'function') requestIdleCallback(prewarmCorrectedMenus);
      else setTimeout(prewarmCorrectedMenus, 50);
    }, {once:true});
  }
"""
if 'FIX116_MENU_CACHE' not in s:
    s=s[:anchor_end]+'\n'+patch+s[anchor_end:]
start='''        const assetUrl = new URL(nativeFlashName, RUNTIME_BASE_URL);
        const response = await fetch(assetUrl, {cache: "no-store"});
        if (!response.ok) throw new Error("Missing native Flash UI asset: " + nativeFlashName);
        data = new Uint8Array(await response.arrayBuffer());'''
replace='''        data = await fixedMenuMovieBytes(nativeFlashName);'''
if replace not in s:
    assert s.count(start)==1, 'Fixed native asset fetch anchor changed'
    s=s.replace(start,replace,1)
target.write_text(s,encoding='utf-8')
print('FIX116_MENU_FETCH_CACHE_PATCHED')
