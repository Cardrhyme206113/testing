#!/usr/bin/env python3
"""Fix87: suppress distorted source graphics only in 3 front-end panel subtrees.

Controls and Flash text are kept in the original Ruffle display tree.
The native 4-pixel fixed-corner backing is drawn under the original SWF.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "ruffle")
p=root/"core/src/display_object/graphic.rs"
s=p.read_text(encoding="utf-8")
marker="LCE_FIX87_NESTED_FRONT_FRAME_GRAPHICS"
if marker in s:raise SystemExit("Fix87 already patched")
old='''    fn render_self(self, context: &mut RenderContext) {
        if !context.is_offscreen'''
new='''    fn render_self(self, context: &mut RenderContext) {
        // LCE_FIX87_NESTED_FRONT_FRAME_GRAPHICS. A Ruffle 0.6 regression
        // stretches nested nine-grid source shapes into enormous corners.
        // These three *front-end-only* movies still show bad black edges after
        // the AVM2 MovieClip scale9 drawing suppression. Keep authored text,
        // buttons, named movie clips, and original input hitboxes intact.
        let mut ancestor = self.parent();
        let mut root_url = self.movie().url().to_string();
        let mut panel_ancestor = false;
        let mut protected_control = false;
        while let Some(a) = ancestor {
            if let Some(mc) = a.as_movie_clip() {
                root_url = mc.movie().url().to_string();
                let name = mc.name().map(|n| n.to_string()).unwrap_or_default();
                if matches!(name.as_str(), "MainPanel" | "Panel_2" | "ButtonListBackground"
                    | "BackgroundPanel" | "TexturePackPanel") {
                    panel_ancestor = true;
                }
                let lname = name.to_ascii_lowercase();
                if lname.contains("button") || lname.contains("checkbox")
                    || lname.contains("slider") || lname.contains("textfield")
                    || lname.contains("edit") || lname.contains("texture")
                    || lname.contains("icon") || lname.contains("label")
                    || lname.contains("scroll") || lname.contains("newworld")
                    || lname.contains("moreoptions") || lname.contains("gamemode")
                    || lname.contains("difficulty") || lname.contains("worldname") {
                    protected_control = true;
                }
            }
            ancestor = a.parent();
        }
        let affected_front = root_url.contains("HowToPlayMenu720.swf")
            || root_url.contains("Controls720.swf")
            || root_url.contains("CreateWorldMenu720.swf");
        if affected_front && panel_ancestor && !protected_control {
            let bounds = self.world_bounds(BoundsMode::Engine);
            let w = bounds.width().to_pixels().abs();
            let h = bounds.height().to_pixels().abs();
            if (w > 80.0 && h > 45.0) || (h > 120.0 && w > 18.0) {
                static HIDDEN: std::sync::atomic::AtomicUsize =
                    std::sync::atomic::AtomicUsize::new(0);
                if HIDDEN.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 45 {
                    tracing::warn!("[LCE-Web][fix87] panel-art-suppressed scene={} w={} h={}",
                        root_url, w, h);
                }
                return;
            }
        }
        if !context.is_offscreen'''
if s.count(old)!=1:raise SystemExit(f"Graphic render anchor occurs {s.count(old)} times")
s=s.replace(old,new,1)
p.write_text(s,encoding="utf-8")
print("Fix87 suppresses only large nested Graphic artwork in three front menus")
