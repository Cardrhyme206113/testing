#!/usr/bin/env python3
"""Fix87 front-end-only fixed-corner backings for two original SWF menu lists."""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "site/lce_web_demo.js")
s=p.read_text(encoding="utf-8")
if "LCE_FIX87_FRONT_NINESLICE_BACKINGS" in s:raise SystemExit("Already installed")
old='''  function tickPlayer(id) {'''
new='''  // LCE_FIX87_FRONT_NINESLICE_BACKINGS:
  // The nested Ruffle source-artwork is now suppressed at Graphic::render_self.
  // Replace only the decorative nine-slice backing. Every authored button,
  // graphic controller diagram, label, and hitbox remains in the original SWF.
  function repairNestedFrontMenuFrame(id, record) {
    if (!record || record.pendingDestroy || !record.visible) return;
    const file = String(record.filename || "");
    if (file !== "HowToPlayMenu720.swf" && file !== "Controls720.swf") return;
    record.__lceFix87FrameTicks = (record.__lceFix87FrameTicks || 0) + 1;
    if (record.__lceFix87FrameTicks % 20 !== 1) return;
    if (file === "HowToPlayMenu720.swf") {
      setNativeScale9Panel(id, "fix87-howto-panel",
        410, 214, 459, 369, 1280, 720, 0, true);
    } else {
      setNativeScale9Panel(id, "fix87-controller-panel",
        410, 196, 460, 128, 1280, 720, 0, true);
    }
  }
  function tickPlayer(id) {'''
if s.count(old)!=1:raise SystemExit("Tick function anchor unexpected")
s=s.replace(old,new,1)
old='''    repairCreditsOriginalScrollViewport(record);
    return true;'''
new='''    repairCreditsOriginalScrollViewport(record);
    repairNestedFrontMenuFrame(id, record);
    return true;'''
if s.count(old)!=1:raise SystemExit("Normal tick patch unavailable")
s=s.replace(old,new,1)
old='''          repairCreditsOriginalScrollViewport(record);
        }
      });'''
new='''          repairCreditsOriginalScrollViewport(record);
          repairNestedFrontMenuFrame(id, record);
        }
      });'''
if s.count(old)!=1:raise SystemExit("Deferred tick patch unavailable")
s=s.replace(old,new,1)
p.write_text(s,encoding="utf-8")
print("Fix87 native fixed-corner front menu backings enabled")
