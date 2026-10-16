#!/usr/bin/env python3
"""Fix95: restore Fix90 targeted suppression of only exact Flash decorative FJ_Panel9Grid sprites.

SWF skinGraphics.swf defines:
  Symbol ID 210 = FJ_Panel9Grid (DefineScalingGrid present)
  Symbol ID 208 = FJ_panel9Grid_16 (DefineScalingGrid present)
They are artwork-only shared frame assets, not interactive controls.
All other original Flash display objects are preserved.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else 'ruffle')
p=root/'core/src/display_object/movie_clip.rs'
s=p.read_text(encoding='utf-8')
marker='LCE_FIX95_EXACT_FJ_PANEL9GRID'
if marker in s:raise SystemExit('Already patched')
needle='''        let name = self.name().map(|name| name.to_string()).unwrap_or_default();
        let scene_help_panel = root_url.contains("SettingsOptionsMenu")'''
new='''        let name = self.name().map(|name| name.to_string()).unwrap_or_default();
        // LCE_FIX90_EXACT_FJ_PANEL9GRID: the shared skinGraphics.swf exports
        // two pure-art nine-grid movie clips by character IDs 208 and 210.
        // Their child graphics are incorrectly uniformly scaled in Ruffle 0.6.
        // Hide *only* those nested decorative sprites in the three front-end
        // scenes that still exhibit huge corners. All other graphics, panorama,
        // controller art, text, button sprites and hitboxes remain intact.
        let affected_front = root_url.contains("HowToPlayMenu720.swf")
            || root_url.contains("Controls720.swf")
            || root_url.contains("CreateWorldMenu720.swf");
        if affected_front && self.scaling_grid().is_valid()
            && matches!(self.id(), 208 | 210)
            && self.movie().url().contains("skinGraphics.swf") {
            return;
        }
        let scene_help_panel = root_url.contains("SettingsOptionsMenu")'''
if s.count(needle)!=1: raise SystemExit(f'Patched Ruffle panel anchor count {s.count(needle)}')
s=s.replace(needle,new,1)
p.write_text(s,encoding='utf-8')
print('Fix95 exact artwork-only nine-grid IDs 208/210 suppressed; original SWF controls kept')
