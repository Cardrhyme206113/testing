#!/usr/bin/env python3
"""Fix genuine disappearing Legacy Console Edition AVM1 controls in compiled Ruffle bridge.

This operates on the original Emscripten/Ruffle build. It does not create HTML controls.
Fix73 geometry-panel patch is retained. Target only the observed More Options AVM1
off-screen sentinel; leave genuine hidden/disabled controls and other menus alone.
"""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "site/lce_web_demo.js")
s=p.read_text(encoding="utf-8")
marker="LCE_WEB_FIX76_RESTORE_ORIGINAL_AVM1_CONTROLS"
if marker in s:
    print("Fix76 already applied")
    raise SystemExit(0)
hook="""  function tickPlayer(id) {
    id |= 0;"""
helper="""  // LCE_WEB_FIX76_RESTORE_ORIGINAL_AVM1_CONTROLS
  // Ruffle AVM1 erroneously leaves eight visible native menu movie clips at
  // -107374182.4px on their local y-axis after Checkbox.Init(). Their correct
  // authored y values were directly observed before init, and confirmed via
  // reversible live-browser experiments. The original checkbox sprites,
  // labels and handlers are restored; no replacement/duplicated buttons.
  function repairMoreOptionsOriginalMovieClips(record) {
    if (!record || record.pendingDestroy ||
        !/LaunchMoreOptionsMenu720\\.swf$/i.test(String(record.filename || ""))) return;
    record.__lceOriginalMenuFix76Frames = (record.__lceOriginalMenuFix76Frames || 0) + 1;
    if (record.__lceOriginalMenuFix76Frames % 10 !== 1) return;
    const yValues = [
      ["CheckboxPVP",170], ["CheckboxTrustSystem",204],
      ["CheckboxFireSpreads",238], ["CheckboxTNT",272],
      ["CheckboxHostPrivileges",306], ["CheckboxStructures",369],
      ["CheckboxFlatWorld",403], ["CheckboxBonusChest",437]
    ];
    for (const [clip,y] of yValues) {
      try {
        const current = Number(record.api.webIggyGet(clip,"y"));
        if (Number.isFinite(current) && current < -100000) {
          record.api.webIggySet(clip,"y",y);
        }
      } catch (error) {
        if (!record.__lceFix76LoggedError) {
          record.__lceFix76LoggedError=true;
          console.warn("[LCE-Web][fix76] original More Options repair", error);
        }
      }
    }
  }
  function tickPlayer(id) {
    id |= 0;"""
if s.count(hook)!=1:raise SystemExit("Could not locate single tickPlayer bridge")
s=s.replace(hook,helper,1)
old="""        if (current === record && !record.pendingDestroy) record.api.webIggyTick(timestamp);"""
new="""        if (current === record && !record.pendingDestroy) {
          record.api.webIggyTick(timestamp);
          repairMoreOptionsOriginalMovieClips(record);
        }"""
if s.count(old)!=1:raise SystemExit("Could not locate deferred tick")
s=s.replace(old,new,1)
old="""    record.api.webIggyTick(timestamp);
    return true;
  }"""
new="""    record.api.webIggyTick(timestamp);
    repairMoreOptionsOriginalMovieClips(record);
    return true;
  }"""
if s.count(old)!=1:raise SystemExit("Could not locate normal tick")
s=s.replace(old,new,1)
p.write_text(s,encoding="utf-8")
print("Fix76 original checkbox sprites automatically restored in Ruffle player tick")
