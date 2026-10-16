#!/usr/bin/env python3
"""Fix96: restore native rotating loading tips and apply panorama-only blur.

Does not change original C++ tip generation, 7-second tip timer,
SWF game controls, or Ruffle renderer. Applied to compiled Emscripten JS.
"""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else 'site/lce_web_demo.js')
s=p.read_text()
if 'LCE_FIX96_ORIGINAL_LOADING_TIPS' in s:
    raise SystemExit('Already patched')

def replace_exact(a,b):
    global s
    if s.count(a)!=1: raise SystemExit(f'Fix96 anchor count={s.count(a)}: {a[:96]!r}')
    s=s.replace(a,b,1)

replace_exact('''    player.dataset.webIggyId = String(id);
    Object.assign(player.style, {''','''    player.dataset.webIggyId = String(id);
    // LCE_FIX96_ORIGINAL_PANORAMA_BLUR: only the independently rendered
    // background panorama is softened. Every overlay and control stays sharp.
    if (/^Panorama(?:Split)?(?:480|720|1080|Vita)[.]swf$/i.test(basename(filename))) {
      player.dataset.lceOriginalPanoramaBlur = "1";
      player.style.filter = "blur(5px)";
    }
    Object.assign(player.style, {''')

replace_exact('''    const call = state.call;
    state.call = null;
    const value = recordFor(call.id).api.webIggyCall(call.path, call.method, call.args);''','''    const call = state.call;
    state.call = null;
    // LCE_FIX96_ORIGINAL_LOADING_TIPS: native UIScene_FullscreenProgress
    // already sends localized GetNextTip() text via Tip.Init / Tip.SetLabel.
    // Ruffle's FJ_HtmlText_WithPanel reports a 0x0 rect, so preserve the
    // *real* native text and make it visible in a browser layout layer.
    const owner = recordFor(call.id);
    if (owner.filename === "FullscreenProgress720.swf" &&
        call.path === "Tip" &&
        (call.method === "Init" || call.method === "SetLabel") &&
        typeof call.args[0] === "string") {
      owner.lce96TipMarkup = call.args[0];
      globalThis.__lce96SeenNativeLabel = (globalThis.__lce96SeenNativeLabel || 0) + 1;
    }
    const value = owner.api.webIggyCall(call.path, call.method, call.args);''')

replace_exact('''  function setValue(id, path, property, value) {
    return !!recordFor(id).api.webIggySet(String(path || ""), String(property || ""), value);
  }''','''  function setValue(id, path, property, value) {
    const record = recordFor(id);
    if (record.filename === "FullscreenProgress720.swf" &&
        String(path) === "Tip" && String(property) === "visible") {
      record.lce96TipRequestedVisible = !!value;
    }
    return !!record.api.webIggySet(String(path || ""), String(property || ""), value);
  }''')

anchor='''  function tickPlayer(id) {'''
inject='''  // LCE_FIX96_ORIGINAL_LOADING_TIPS: replaces only the damaged Flash Tip
  // composite (an empty 0x0 text field and oversized white nine-grid).
  // Title/progress bar remain 100% native Iggy/Ruffle.
  function repairOriginalLoadingTip(record) {
    if (record.filename !== "FullscreenProgress720.swf" ||
        !state.root || record.pendingDestroy) return;
    globalThis.__lce96RepairCalled = (globalThis.__lce96RepairCalled || 0) + 1;
    if (record.lce96TipRequestedVisible === undefined) {
      try { record.lce96TipRequestedVisible =
        record.api.webIggyGet("Tip", "visible") !== false; }
      catch (_) { record.lce96TipRequestedVisible = false; }
    }
    let overlay = record.lce96TipOverlay;
    const hasText = !!String(record.lce96TipMarkup || "").replace(/<[^>]*>/g, "").trim();
    const show = !!record.lce96TipRequestedVisible && hasText && record.visible;
    globalThis.__lce96Debug = {id:record.id, requested:record.lce96TipRequestedVisible, visible:record.visible, hasText, markup:String(record.lce96TipMarkup||'').slice(0,240), actualVisible:record.api.webIggyGet('Tip','visible'), show};
    if (!show) {
      if (overlay) { overlay.dataset.lcePanelVisible = "0"; overlay.style.visibility = "hidden"; }
      return;
    }
    // Do not hide any Ruffle player, progress bar, title or other Flash art.
    // Hide only the defective composite at the original exported "Tip" path.
    try { record.api.webIggySet("Tip", "visible", false); } catch (_) {}
    if (!overlay) {
      overlay = document.createElement("div");
      overlay.className = "lce-webiggy-loading-native-tip";
      overlay.dataset.lcePanelKey = "fix96-real-loading-tip";
      overlay.dataset.lcePanelVisible = "1";
      Object.assign(overlay.style, {
        position: "absolute", display: "flex", alignItems: "center",
        justifyContent: "center", boxSizing: "border-box",
        background: "rgba(27,39,43,0.92)", color: "#ebebeb",
        border: "3px solid #b6b6b6",
        boxShadow: "inset 2px 2px #eeeeee, inset -2px -2px #444444, 3px 3px rgba(0,0,0,.55)",
        padding: "8px 24px", textAlign: "center", lineHeight: "1.25",
        fontFamily: "monospace", fontWeight: "bold", whiteSpace: "normal",
        pointerEvents: "none", touchAction: "none", overflow: "hidden",
        textShadow: "2px 2px 0 #111"
      });
      state.root.insertBefore(overlay, record.player.nextSibling);
      record.lce96TipOverlay = overlay;
      record.nativeScale9Panels ||= new Map();
      record.nativeScale9Panels.set("fix96-real-loading-tip", overlay);
    }
    const plain = String(record.lce96TipMarkup || "")
      .replace(/<[^>]*>/g, "").replace(/&amp;/g,"&")
      .replace(/&lt;/g,"<").replace(/&gt;/g,">")
      .replace(/&quot;/g,'"').replace(/&#39;/g,"'").trim();
    if (overlay.textContent !== plain) overlay.textContent = plain;
    const {stageLeft,stageTop,stageW,stageH} = stageRectFor(record,1280,720);
    Object.assign(overlay.style, {
      left: (stageLeft + 240*stageW/1280) + "px",
      top: (stageTop + 512*stageH/720) + "px",
      width: (734*stageW/1280) + "px",
      height: (92*stageH/720) + "px",
      fontSize: Math.max(10, Math.round(16*stageW/1280)) + "px",
      zIndex: record.player.style.zIndex || "0",
      visibility: record.visible ? "visible" : "hidden"
    });
    overlay.dataset.lcePanelVisible = "1";
  }
'''
replace_exact(anchor,inject+anchor)
# Hook into both deferred and ordinary native tick paths.
replace_exact('''          repairCreditsOriginalScrollViewport(record);
        }
      });''','''          repairCreditsOriginalScrollViewport(record);
          repairOriginalLoadingTip(record);
        }
      });''')
replace_exact('''    repairCreditsOriginalScrollViewport(record);
    return true;''','''    repairCreditsOriginalScrollViewport(record);
    repairOriginalLoadingTip(record);
    return true;''')
p.write_text(s)
print('Fix96: real localized rotating tip restored, only Panorama SWF blurred')
