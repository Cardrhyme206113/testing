#!/usr/bin/env python3
"""Don't paint Flash's Alpha blend sprite as an opaque black wall over credits.

Credits720.swf's BlendingMask is alpha-compositing artwork, not foreground
paint. The legacy Ruffle renderer incorrectly draws this mask in front of
scrolling text. The replacement alpha-gradient is applied to the independent
Credits Ruffle player in the WebIggy host; the authored labels are untouched.
"""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "ruffle")/"core/src/display_object.rs"
s=p.read_text()
if "LCE_FIX94_CREDITS_BLEND_MASK_FOREGROUND" in s:raise SystemExit("Already patched")
old='''    if options.skip_masks && this.maskee().is_some() {'''
new='''    // LCE_FIX94_CREDITS_BLEND_MASK_FOREGROUND
    // Flash BlendMode.Alpha is a mask, not a black rectangle above the text.
    // Ruffle 0.6 does not compose this Credits-specific AS2 layer correctly.
    // Keep the movie clip alive for its timeline; omit its *foreground draw*.
    if this.name().map(|n| n.to_string()).as_deref() == Some("BlendingMask")
        && this.movie().url().contains("Credits720.swf") {
        return;
    }
    if options.skip_masks && this.maskee().is_some() {'''
if s.count(old)!=1: raise SystemExit("render_base mask hook changed")
s=s.replace(old,new,1)
p.write_text(s)
print("Fix94: suppress only Credits's erroneously opaque alpha mask foreground")
