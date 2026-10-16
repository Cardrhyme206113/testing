#!/usr/bin/env python3
"""Fix credits at the actual text-pixel renderer, not per-field visibility.

Ruffle 0.6 does not honor the authored Credits720 BlendingMask composite.
An additional stencil clips glyph pixels to the original fade viewport,
preserving characters/lines partially inside the window.
"""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "ruffle")/"core/src/display_object/edit_text.rs"
s=p.read_text()
marker="LCE_FIX93_CREDITS_GLYPH_VIEWPORT"
if marker in s:raise SystemExit("Already patched")
old='''        context.commands.push_mask();

        let mask_bounds = self.0.bounds.get().grow_x(-Self::GUTTER);'''
new='''        // LCE_FIX93_CREDITS_GLYPH_VIEWPORT: the original Credits720.swf
        // uses a BlendingMask alpha gradient, ignored by Ruffle 0.6.
        // Compose the stage-space viewport with each EditText's native mask
        // so scrolling GLYPHS are cropped, never entire EditText objects.
        // Coordinates are from the authored 1280x720 credits viewport.
        let mut lce_credits_scene = self.movie().url().contains("Credits720.swf");
        let mut ancestor = self.parent();
        while let Some(parent) = ancestor {
            if let Some(movieclip) = parent.as_movie_clip() {
                if movieclip.movie().url().contains("Credits720.swf") {
                    lce_credits_scene = true;
                    break;
                }
            }
            ancestor = parent.parent();
        }
        let lce_credits_clip = if lce_credits_scene {
            let stage_bounds = Rectangle {
                x_min: Twips::from_pixels(176.0),
                x_max: Twips::from_pixels(1112.0),
                y_min: Twips::from_pixels(258.0),
                y_max: Twips::from_pixels(583.0),
            };
            let local_from_stage = self.global_to_local_matrix().unwrap_or_default();
            Some(context.transform_stack.transform().matrix
                * local_from_stage
                * Matrix::create_box_from_rectangle(&stage_bounds))
        } else { None };
        if let Some(mat) = lce_credits_clip {
            context.commands.push_mask();
            context.commands.draw_rect(Color::WHITE, mat);
            context.commands.activate_mask();
        }

        context.commands.push_mask();

        let mask_bounds = self.0.bounds.get().grow_x(-Self::GUTTER);'''
if s.count(old)!=1:raise SystemExit("Credits text-render hook not found exactly once")
s=s.replace(old,new,1)
old='''        if let Some(draw_caret_command) = render_state.draw_caret_command {
            context.commands.commands.push(draw_caret_command);
        }
    }

    fn allow_as_mask(self) -> bool {'''
new='''        if let Some(draw_caret_command) = render_state.draw_caret_command {
            context.commands.commands.push(draw_caret_command);
        }
        if let Some(mat) = lce_credits_clip {
            context.commands.deactivate_mask();
            context.commands.draw_rect(Color::WHITE, mat);
            context.commands.pop_mask();
        }
    }

    fn allow_as_mask(self) -> bool {'''
if s.count(old)!=1:raise SystemExit("Credits text mask teardown hook not found exactly once")
s=s.replace(old,new,1)
p.write_text(s)
print("Fix93 Credits text viewport clipped per pixel; all original text retained")
