#!/usr/bin/env python3
"""Fix original Credits720.swf alpha blend mask at MovieClip compositor level."""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "ruffle")/"core/src/display_object/movie_clip.rs"
s=p.read_text()
marker="LCE_FIX92_CREDITS_ALPHA_COMPOSITION"
if marker in s: raise SystemExit("already patched")
old='''        let scene_help_panel = root_url.contains("SettingsOptionsMenu")'''
new='''        // LCE_FIX92_CREDITS_ALPHA_COMPOSITION
        // The original Credits720.swf has BlendingContainer (Layer) with
        // BlendingMask (Alpha) drawn after the animated credits labels.
        // On Ruffle 0.6 the unsupported sprite-level composite shows the
        // gradient as a black wall and leaks text below. Render the animated
        // children to a command list and alpha-mask that list with the
        // *original SWF* BlendingMask, preserving partial lines / gradients.
        if root_url.contains("Credits720.swf") && name == "BlendingContainer" {
            let stencil = self.iter_render_list().find(|child| {
                child.name().map(|n| n.to_string()).as_deref() == Some("BlendingMask")
            });
            if let Some(stencil) = stencil {
                // Preserve the authored black gradient backdrop.
                stencil.render(context);
                let original = std::mem::take(&mut context.commands);
                for child in self.iter_render_list() {
                    if child.name().map(|n| n.to_string()).as_deref() == Some("BlendingMask") {
                        continue;
                    }
                    child.pre_render(context);
                    if child.visible() { child.render(context); }
                }
                let content = std::mem::take(&mut context.commands);
                stencil.render(context);
                let mask = std::mem::replace(&mut context.commands, original);
                context.commands.render_alpha_mask(content, mask);
                return;
            }
        }
        let scene_help_panel = root_url.contains("SettingsOptionsMenu")'''
if s.count(old)!=1: raise SystemExit("expected one patched Ruffle render anchor; got %d"%s.count(old))
s=s.replace(old,new,1)
# CommandList::render_alpha_mask is a trait method.
use_anchor = "use ruffle_render::perspective_projection::PerspectiveProjection;"
use_line = "use ruffle_render::commands::CommandHandler;"
if use_line not in s:
    if s.count(use_anchor)!=1: raise SystemExit("render command import anchor missing")
    s=s.replace(use_anchor,use_anchor + "\n" + use_line,1)
p.write_text(s)
print("Original Credits720.BlendingMask alpha compositing installed")
