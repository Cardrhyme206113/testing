#!/usr/bin/env python3
"""Fix shared Ruffle scale9 backgrounds for independent background clips.

Extends Fix80 to all SWF UI movies exposing BackgroundPanel or BackgroundPanel2.
Nested MainPanel-only movies remain untouched until core Ruffle can be rebuilt.
Original Flash widgets and hit testing are preserved.
"""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "site/lce_web_demo.js")
s=p.read_text()
MARK="LCE_WEB_FIX82_GENERIC_NINESLICE_CLIPS"
if MARK in s: raise SystemExit("Fix82 already applied")
start=s.index('  function repairSettingsOriginalNineSliceFrame(id, record) {')
end=s.index('  function tickPlayer(id) {',start)
new='''  // LCE_WEB_FIX82_GENERIC_NINESLICE_CLIPS
  // Draw independent Flash BackgroundPanel clips using fixed-size bevels.
  // Never hide MainPanel: it may contain all authored child controls.
  function repairSettingsOriginalNineSliceFrame(id, record) {
    if (!record || record.pendingDestroy ||
        !/[.]swf$/i.test(String(record.filename || ""))) return;
    record.__lceFix80Ticks = (record.__lceFix80Ticks || 0) + 1;
    if (record.__lceFix80Ticks % 10 !== 1) return;
    try {
      const backgrounds = ["BackgroundPanel", "BackgroundPanel2"];
      for (const name of backgrounds) {
        if (!record.api.webIggyPathExists(name)) continue;
        const values = ["x", "y", "width", "height"].map(axis => {
          const stage = Number(record.api.webIggyGet(name, "__lce_stage_" + axis));
          const local = Number(record.api.webIggyGet(name, axis));
          return Number.isFinite(stage) && stage > -2000 && stage < 3000
              ? stage : local;
        });
        const [x, y, width, height] = values;
        if (![x,y,width,height].every(Number.isFinite) ||
            width < 60 || height < 45 || width > 1280 || height > 720 ||
            x < -100 || y < -100) continue;
        if (record.api.webIggyGet(name, "visible") !== false) {
          record.api.webIggySet(name, "visible", false);
        }
        const key = name === "BackgroundPanel"
            ? "fix80-settings-nine-slice-frame"
            : "fix82-second-nine-slice-frame";
        setNativeScale9Panel(id, key, Math.round(x), Math.round(y),
          Math.round(width), Math.round(height),
          record.displayWidth || 1280, record.displayHeight || 720, 0, true);
      }
    } catch (error) {
      if (!record.__lceFix82LoggedError) {
        record.__lceFix82LoggedError=true;
        console.warn("[LCE-Web][fix82] independent background", error);
      }
    }
  }
'''
s=s[:start]+new+s[end:]
p.write_text(s)
print("Fix82: independent Flash BackgroundPanel clips use fixed-corner rendering")
