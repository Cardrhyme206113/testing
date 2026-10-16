#!/usr/bin/env python3
"""Restore alpha-faded original Credits text and fixed-edge Create World chrome."""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "site/lce_web_demo.js")
s=p.read_text()
if "LCE_FIX94_ORIGINAL_CREDITS_AND_CREATE_CHROME" in s:raise SystemExit("Already patched")
old='''  function repairCreditsOriginalScrollViewport(_record) {}'''
new='''  // LCE_FIX94_ORIGINAL_CREDITS_AND_CREATE_CHROME.
  // Credits SWF's actual text remains in the original Ruffle movie. The
  // broken Flash BlendMode.Alpha foreground is suppressed in Ruffle source.
  // Put the dark fade BEHIND it and apply pixel-level CSS alpha to that
  // independent movie only. Every partially entering/leaving glyph fades.
  function repairCreditsOriginalScrollViewport(record) {
    if (!record || !record.player || !state.root) return;
    const file = String(record.filename || "");
    if (file === "Credits720.swf") {
      if (record.__lce94CreditsBackdrop) return;
      record.__lce94CreditsBackdrop = true;
      record.player.style.clipPath = "none";
      record.player.style.maskImage =
        "linear-gradient(to bottom, transparent 0%, transparent 34%, black 42%, black 77%, transparent 84%, transparent 100%)";
      record.player.style.webkitMaskImage = record.player.style.maskImage;
      record.nativeScale9Panels ||= new Map();
      const layer = document.createElement("canvas");
      layer.className = "lce-webiggy-credits-backdrop";
      layer.dataset.lcePanelKey = "fix94-credits-dark-gradient";
      layer.dataset.lcePanelVisible = "1";
      layer.width = 1280; layer.height = 720;
      Object.assign(layer.style, {
        position: "absolute", pointerEvents: "none", touchAction: "none",
        background: "transparent",
        zIndex: record.player.style.zIndex || "0"
      });
      const ctx = layer.getContext("2d", {alpha:true});
      const grad = ctx.createLinearGradient(0, 234, 0, 608);
      grad.addColorStop(0, "rgba(0,0,0,0)");
      grad.addColorStop(.16, "rgba(0,0,0,.97)");
      grad.addColorStop(.84, "rgba(0,0,0,.97)");
      grad.addColorStop(1, "rgba(0,0,0,0)");
      ctx.fillStyle = grad;
      ctx.fillRect(176, 234, 936, 374);
      state.root.insertBefore(layer, record.player);
      record.nativeScale9Panels.set("fix94-credits-dark-gradient", layer);
      const {stageLeft,stageTop,stageW,stageH}=stageRectFor(record,1280,720);
      Object.assign(layer.style, {
        left: stageLeft+"px", top:stageTop+"px",
        width:stageW+"px",height:stageH+"px",
        visibility:record.visible ? "visible" : "hidden"
      });
      return;
    }
    if (file !== "CreateWorldMenu720.swf") return;
    // Original Create World animated two-pane UI is still Ruffle-controlled.
    // Only the outside 18px of MainPanel is decorative; paint a crisp 4px
    // bevel ABOVE the stretched old corners, but keep the entire original
    // interior: world name, seed, texture selection, difficulty, and actions.
    const panel = record.nativeScale9Panels?.get("createworld-main");
    const box = panel?.__lceStageRect;
    if (!box || box.width < 200 || box.height < 200) return;
    record.__lce94FrameTicks=(record.__lce94FrameTicks||0)+1;
    if (record.__lce94FrameTicks%10 !== 1) return;
    let layer=record.__lce94FrameOverlay;
    if (!layer) {
      layer=document.createElement("canvas");
      layer.className="lce-webiggy-createworld-frame-cleanup";
      layer.dataset.lcePanelKey="fix94-createworld-crisp-edge";
      layer.dataset.lcePanelVisible="1";
      layer.width=1280; layer.height=720;
      Object.assign(layer.style,{
        position:"absolute", pointerEvents:"none",touchAction:"none",
        background:"transparent",zIndex:record.player.style.zIndex||"0"
      });
      state.root.insertBefore(layer,record.player.nextSibling);
      record.nativeScale9Panels.set("fix94-createworld-crisp-edge",layer);
      record.__lce94FrameOverlay=layer;
    }
    const ctx=layer.getContext("2d",{alpha:true});
    const x=Math.round(box.x), y=Math.round(box.y),
          w=Math.round(box.width), h=Math.round(box.height);
    const band=18, edge=4;
    ctx.clearRect(0,0,1280,720);
    ctx.fillStyle="#c6c6c6";
    ctx.fillRect(x,y,w,band);ctx.fillRect(x,y+h-band,w,band);
    ctx.fillRect(x,y,band,h);ctx.fillRect(x+w-band,y,band,h);
    ctx.fillStyle="#f7f7f7";
    ctx.fillRect(x,y,w,edge);ctx.fillRect(x,y,edge,h);
    ctx.fillStyle="#555555";
    ctx.fillRect(x+w-edge,y,edge,h);ctx.fillRect(x,y+h-edge,w,edge);
    const {stageLeft,stageTop,stageW,stageH}=stageRectFor(record,1280,720);
    Object.assign(layer.style,{
      left:stageLeft+"px",top:stageTop+"px",
      width:stageW+"px",height:stageH+"px",
      visibility:record.visible?"visible":"hidden"
    });
  }'''
if s.count(old)!=1:raise SystemExit("Credits callback from Fix88 unclip was not found")
s=s.replace(old,new,1)
p.write_text(s)
print("Fix94 stage Credits alpha-gradient and Create World border-only overlay installed")
