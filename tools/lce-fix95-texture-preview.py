#!/usr/bin/env python3
"""Fix95: hide only an overlapping decorative Create World texture preview pane."""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else 'site/lce_web_demo.js')
s=p.read_text()
if 'LCE_FIX95_OVERLAP_ONLY_TEXTURE_PREVIEW' in s:
    raise SystemExit('Already applied')
old='''    if (file !== "CreateWorldMenu720.swf") return;
    // Original Create World animated two-pane UI is still Ruffle-controlled.'''
new='''    if (file !== "CreateWorldMenu720.swf") return;
    // LCE_FIX95_OVERLAP_ONLY_TEXTURE_PREVIEW: the right-hand texture preview
    // is a separate Flash movie. During a collapsed Create World dialog,
    // old Ruffle sometimes draws it ON TOP of the left-hand world settings
    // instead of beyond the panel. Do not move the icon carousel, its hit
    // boxes, the world settings, or any other widget. Hide just this separate
    // preview movie while its stage rectangle overlaps the settings pane.
    try {
      const main = record.nativeScale9Panels?.get("createworld-main")?.__lceStageRect;
      const name = "TexturePackPanel";
      if (main && record.api.webIggyPathExists(name)) {
        const get = (axis) => Number(record.api.webIggyGet(name, axis));
        const x = get("__lce_stage_x"), y = get("__lce_stage_y");
        const w = get("width"), h = get("height");
        if ([x,y,w,h].every(Number.isFinite) && w > 0 && h > 0) {
          const ix = Math.max(0, Math.min(x+w,main.x+main.width)-Math.max(x,main.x));
          const iy = Math.max(0, Math.min(y+h,main.y+main.height)-Math.max(y,main.y));
          const overlapping = ix*iy > w*h*0.16;
          if (overlapping) {
            if (record.api.webIggyGet(name,"visible") !== false) {
              record.api.webIggySet(name,"visible",false);
              record.__lce95HiddenPreview = true;
            }
          } else if (record.__lce95HiddenPreview) {
            record.api.webIggySet(name,"visible",true);
            record.__lce95HiddenPreview = false;
          }
        }
      }
    } catch (e) {
      if (!record.__lce95PreviewWarned) {
        record.__lce95PreviewWarned = true;
        console.warn("[LCE-Web][fix95] decorative texture pane",e);
      }
    }
    // Original Create World animated two-pane UI is still Ruffle-controlled.'''
if s.count(old)!=1:raise SystemExit(f'Fix94 Create World hook count {s.count(old)}, refusing patch')
p.write_text(s.replace(old,new,1))
print('Fix95: hide only overlapping TexturePackPanel artwork; retain controls and live geometry')
