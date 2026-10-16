#!/usr/bin/env python3
"""Remove obsolete CSS whole-Credits-player clipping (hides panorama).

Original credits text clipping now lives in Ruffle EditText::render_self.
"""
from pathlib import Path
import re,sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "site/lce_web_demo.js")
s=p.read_text(encoding="utf-8")
mark="LCE_FIX88_CREDITS_CSS_CLIP_REMOVED"
if mark in s:raise SystemExit("Already patched")
a=s.find("  function repairCreditsOriginalScrollViewport(record) {")
b=s.find("  function tickPlayer(id) {",a)
if a<0 or b<0:raise SystemExit("Expected Credits callback missing")
section=s[a:b]
# Replace only the Credits callback; preserve any Fix87 panel helpers between it and tickPlayer.
pattern=r'  function repairCreditsOriginalScrollViewport\(record\) \{.*?\n  \}'
replacement='''  // LCE_FIX88_CREDITS_CSS_CLIP_REMOVED: text mask lives in source Ruffle.
  function repairCreditsOriginalScrollViewport(_record) {}'''
ss,n=re.subn(pattern,replacement,section,count=1,flags=re.S)
if n!=1:raise SystemExit("Credit callback regexp mismatched")
s=s[:a]+ss+s[b:]
old='''    if (/^(Credits|CreditsMenu)720[.]swf$/i.test(String(record.filename || ""))) {
      const clip = "inset(33% 13% 16.5% 13%)";
      record.player.style.clipPath = clip;
      record.player.style.webkitClipPath = clip;
      return;
    }
'''
if s.count(old)!=1:raise SystemExit("Credit tile clip block mismatched")
s=s.replace(old,"",1)
p.write_text(s,encoding="utf-8")
print("Fix88: whole-player credit crop reverted; panoramic background preserved")
