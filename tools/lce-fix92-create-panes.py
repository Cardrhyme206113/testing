#!/usr/bin/env python3
"""Fix decorative backing overlap while retaining Create World's two original panes."""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "site/lce_web_demo.js")
s=p.read_text()
marker="LCE_FIX92_CREATEWORLD_NONOVERLAPPING_PANES"
if marker in s:raise SystemExit("already patched")
old='''    style = Number(style) | 0;'''
new='''    // LCE_FIX92_CREATEWORLD_NONOVERLAPPING_PANES: the texture pack list is
    // authored inside MainPanel. Its native backing had been drawn a second
    // time over the world-name, seed and buttons. Only draw the distinct
    // TexturePackPanel backing when the original SWF slides it to one side.
    if (key === "createworld-texture-list") visible = false;
    if (key === "createworld-texture-panel" && visible) {
      const main = record.nativeScale9Panels?.get("createworld-main");
      const r = main?.__lceStageRect;
      if (r) {
        const ix = Math.max(0, Math.min(x + width, r.x + r.width) - Math.max(x, r.x));
        const iy = Math.max(0, Math.min(y + height, r.y + r.height) - Math.max(y, r.y));
        if (ix * iy > width * height * 0.16) visible = false;
      }
    }
    style = Number(style) | 0;'''
if s.count(old)!=1:raise SystemExit("scale9 panel anchor count=%d"%s.count(old))
s=s.replace(old,new,1)
start=s.index("  function setNativeScale9Panel(")
end=s.index("  function setNativeMenuHelpPanel(",start)
part=s[start:end]
anchor='''    if (overlay.width !== screenWidth || overlay.height !== screenHeight) {'''
if part.count(anchor)!=1:raise SystemExit("native panel canvas anchor missing")
part=part.replace(anchor,'''    // Store measured stage bounds for the next C++ scene tick.
    overlay.__lceStageRect = {x, y, width, height};
'''+anchor,1)
s=s[:start]+part+s[end:]
p.write_text(s)
print("Create World embedded texture list no longer duplicates its main frame")
