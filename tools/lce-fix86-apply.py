#!/usr/bin/env python3
"""Fix86: fix credit-scroll clipping and World Options excess empty backing.

Limited to original WebIggy movie surfaces. Does NOT reposition the Flash
checkboxes, change their event handlers, or alter in-game inventory/menus.
"""
import sys
from pathlib import Path
p=Path(sys.argv[1] if len(sys.argv)>1 else "site/lce_web_demo.js")
s=p.read_text(encoding="utf-8")
if "LCE_FIX86_CREDITS_VIEWPORT" in s:
    print("Already patched")
    sys.exit(0)
old='''    if (key === "world-options-main") height = Math.max(height, Math.round(screenHeight * .47));'''
new='''    // LCE_FIX86_WORLDOPTIONS_BACKING: retain its authored right/left/bottom
    // and fit the native backing to the first visible control rather than
    // painting 125px of empty grey space above the checkbox list.
    if (key === "world-options-main") {
      const formerBottom = y + height;
      const desiredTop = Math.round(screenHeight * 133 / 720);
      if (y < desiredTop) {
        y = desiredTop;
        height = Math.max(1, formerBottom - desiredTop);
      }
    }'''
if s.count(old)!=1: raise SystemExit("World Options backing anchor changed")
s=s.replace(old,new,1)
anchor='''    record.api.webIggyTick(timestamp);
    repairMoreOptionsOriginalMovieClips(record);
          repairSettingsOriginalNineSliceFrame(id, record);
    return true;'''
replace='''    record.api.webIggyTick(timestamp);
    repairMoreOptionsOriginalMovieClips(record);
    repairSettingsOriginalNineSliceFrame(id, record);
    repairCreditsOriginalScrollViewport(record);
    return true;'''
if s.count(anchor)!=1:raise SystemExit("tick anchor missing")
s=s.replace(anchor,replace,1)
anchor='''          repairMoreOptionsOriginalMovieClips(record);
          repairSettingsOriginalNineSliceFrame(id, record);
        }
      });'''
replace='''          repairMoreOptionsOriginalMovieClips(record);
          repairSettingsOriginalNineSliceFrame(id, record);
          repairCreditsOriginalScrollViewport(record);
        }
      });'''
if s.count(anchor)!=1:raise SystemExit("deferred tick anchor missing")
s=s.replace(anchor,replace,1)
anchor='''  function tickPlayer(id) {'''
insert='''  // LCE_FIX86_CREDITS_VIEWPORT: original CreditsMenu720.swf animates the
  // authentic names/roles; old Ruffle fails to enforce its authored mask,
  // allowing the scrolling text to escape below the black fade window.
  // Clip this one movie surface to the observed centered credits aperture.
  // The separate Panorama, Logo and ToolTips players stay unaffected.
  function repairCreditsOriginalScrollViewport(record) {
    if (!record || record.pendingDestroy ||
        !/^CreditsMenu720[.]swf$/i.test(String(record.filename || ""))) return;
    if (record.__lceFix86CreditsClipped) return;
    record.__lceFix86CreditsClipped = true;
    const clip = "inset(33% 13% 16.5% 13%)";
    record.player.style.clipPath = clip;
    record.player.style.webkitClipPath = clip;
  }
  function tickPlayer(id) {'''
if s.count(anchor)!=1:raise SystemExit("tick anchor changed")
s=s.replace(anchor,insert,1)
p.write_text(s,encoding="utf-8")
print("Fix86: Credits scrolling content masked; World Options backing resized")
