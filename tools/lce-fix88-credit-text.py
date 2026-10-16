#!/usr/bin/env python3
"""Fix88 original Credits scrolling text viewport, scoped to EditText only.

Credits720 SWF owns panorama/background AND scroll text. Clipping the whole
player hides the panorama; rendering bounds must be applied to credit labels
only. Original text content, fonts, and scroll animation are preserved.
"""
import sys
from pathlib import Path
root=Path(sys.argv[1] if len(sys.argv)>1 else "ruffle")
p=root/"core/src/display_object/edit_text.rs"
s=p.read_text(encoding="utf-8")
marker="LCE_FIX88_CREDITS_TEXT_ONLY_VIEWPORT"
if marker in s:raise SystemExit("Already patched")
old='''        self.apply_autosize_bounds();

        if !context.is_offscreen'''
new='''        self.apply_autosize_bounds();
        // LCE_FIX88_CREDITS_TEXT_ONLY_VIEWPORT: the credits movie draws its
        // own panorama plus a fading black text window. Ruffle ignores the
        // mask of the animated EditText credits. Restrict only the text
        // objects to that window; leave background, logo and tooltips alone.
        let mut credits_root_url = self.movie().url().to_string();
        let mut credit_ancestor = self.parent();
        while let Some(parent) = credit_ancestor {
            if let Some(movieclip) = parent.as_movie_clip() {
                credits_root_url = movieclip.movie().url().to_string();
            }
            credit_ancestor = parent.parent();
        }
        if credits_root_url.contains("Credits720.swf") {
            let area = self.world_bounds(BoundsMode::Engine);
            let top = area.y_min.to_pixels();
            let bottom = area.y_max.to_pixels();
            // 720p authored stage coordinates, excluding the 40px HTML
            // letterbox. Only fully visible rows render; no scroll text
            // can leak past the original 300..606px fade aperture.
            if top < 254.0 || bottom > 565.0 {
                return;
            }
        }

        if !context.is_offscreen'''
if s.count(old)!=1: raise SystemExit(f"EditText render hook occurs {s.count(old)} times")
s=s.replace(old,new,1)
p.write_text(s,encoding="utf-8")
print("Fix88: Credits text-only scissor window inserted into source Ruffle renderer")
