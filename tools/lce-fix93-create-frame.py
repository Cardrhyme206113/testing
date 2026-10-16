#!/usr/bin/env python3
"""Target only the two art-only 9-grid fade classes exported by original skin.swf."""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "ruffle")/"core/src/display_object/movie_clip.rs"
s=p.read_text()
if "LCE_FIX93_ORIGINAL_CREATE_FADE_PANEL" in s:raise SystemExit("Already patched")
old='''        let scene_help_panel = root_url.contains("SettingsOptionsMenu")'''
new='''        // LCE_FIX93_ORIGINAL_CREATE_FADE_PANEL. The original skin.swf exports
        // FJ_Panel9GridFade (id 165) and FJ_panel9Grid_16Fade (id 167).
        // Each is an artwork-only timeline: one PlaceObject child and frame
        // animation, no interactive controls. Hide only these old distorted
        // corner sprites in Create World. Its native fixed-edge backing is
        // already drawn under the unchanged C++/Flash widgets.
        if root_url.contains("CreateWorldMenu720.swf")
            && self.movie().url().contains("skin.swf")
            && matches!(self.id(), 165 | 167) {
            static HIDDEN_COUNT: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            if HIDDEN_COUNT.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 5 {
                tracing::warn!("[LCE-Web][fix93] suppressed original oversized fade panel: id={}", self.id());
            }
            return;
        }
        let scene_help_panel = root_url.contains("SettingsOptionsMenu")'''
if s.count(old)!=1:raise SystemExit("MovieClip frame repair hook not found exactly once")
s=s.replace(old,new,1)
p.write_text(s)
print("Fix93 skipped only skin.swf art-only scale9 fade classes 165 and 167 in Create World")
