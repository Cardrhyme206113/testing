#!/usr/bin/env python3
"""Globally correct Settings SWF nine-slice frame rendering, without fake controls."""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "site/lce_web_demo.js")
s=p.read_text()
MARKER="LCE_WEB_FIX80_SCALE9_BACKGROUND_FRAME"
if MARKER in s:raise SystemExit("Fix80 already applied")
old="""  function tickPlayer(id) {
    id |= 0;"""
new="""  // LCE_WEB_FIX80_SCALE9_BACKGROUND_FRAME.
  // The installed Ruffle WebIggy version stores nine-slice metadata but draws
  // the original FJ_Panel9Grid as if it were scaled linearly: corners become
  // disproportionately huge. Settings menus have a separate BackgroundPanel
  // SWF clip; hiding it leaves all original Flash checkboxes/sliders/text intact.
  // Paint only the background beneath the original SWF via the existing native
  // 4px panel painter (setNativeScale9Panel), using the SWF's own authored bounds.
  function repairSettingsOriginalNineSliceFrame(id, record) {
    if (!record || record.pendingDestroy ||
        !/^Settings[A-Za-z]*Menu720\\.swf$/i.test(String(record.filename || ""))) return;
    record.__lceFix80Ticks = (record.__lceFix80Ticks || 0) + 1;
    if (record.__lceFix80Ticks % 10 !== 1) return;
    try {
      const path = "BackgroundPanel";
      if (!record.api.webIggyPathExists(path)) return;
      const values = ["x", "y", "width", "height"].map(axis => {
        const stage = Number(record.api.webIggyGet(path, "__lce_stage_" + axis));
        const local = Number(record.api.webIggyGet(path, axis));
        return Number.isFinite(stage) && stage > -2000 && stage < 3000
            ? stage : local;
      });
      const [x, y, width, height] = values;
      if (![x,y,width,height].every(Number.isFinite) ||
          width < 60 || height < 45 || width > 1280 || height > 720 ||
          x < -100 || y < -100) return;
      if (record.api.webIggyGet(path, "visible") !== false) {
        record.api.webIggySet(path, "visible", false);
      }
      setNativeScale9Panel(id, "fix80-settings-nine-slice-frame",
        Math.round(x), Math.round(y),
        Math.round(width), Math.round(height),
        record.displayWidth || 1280, record.displayHeight || 720, 0, true);
    } catch (error) {
      if (!record.__lceFix80LoggedError) {
        record.__lceFix80LoggedError = true;
        console.warn("[LCE-Web][fix80] settings scale9 panel", error);
      }
    }
  }
  function tickPlayer(id) {
    id |= 0;"""
if s.count(old)!=1:raise SystemExit(f"tickPlayer hook occurs {s.count(old)} times; refusing patch")
s=s.replace(old,new,1)
anchor="repairMoreOptionsOriginalMovieClips(record);"
if s.count(anchor)!=2:raise SystemExit(f"wrong tick hook count {s.count(anchor)}")
s=s.replace(anchor,anchor+"\n          repairSettingsOriginalNineSliceFrame(id, record);")
p.write_text(s)
print("Fix80 native 9-slice background rendering installed for Settings SWFs")
