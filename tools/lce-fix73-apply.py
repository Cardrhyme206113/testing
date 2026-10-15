#!/usr/bin/env python3
"""Patch compiled Fix70 LCE WebIggy JS; narrow Fix73 native Flash UI compatibility."""
from pathlib import Path
import sys

target = Path(sys.argv[1] if len(sys.argv) > 1 else "site/lce_web_demo.js")
src = target.read_text(encoding="utf-8")
if "LCE_WEB_FIX73_OPTIONS_GEOMETRY" in src:
    print("Fix73 already installed")
    sys.exit(0)

old = '''function getValue(id, path, property) {
    const value = recordFor(id).api.webIggyGet(String(path || ""), String(property || ""));
    setLast(value);
    return value;
  }'''
new = '''function getValue(id, path, property) {
    const record = recordFor(id);
    const clip = String(path || "");
    const prop = String(property || "");
    let value = record.api.webIggyGet(clip, prop);
    // LCE_WEB_FIX73_OPTIONS_GEOMETRY: the original AVM1 menu reports correct
    // local control coordinates during init but some become INT_MIN/20 once
    // the Flash timeline advances. Cache only real, finite original values.
    if (/LaunchMoreOptionsMenu/i.test(String(record.filename || ""))) {
      record.__lceFix73Original ||= new Map();
      const isDimension = axis => axis === "width" || axis === "height";
      const sane = (n, axis) => Number.isFinite(n) && Math.abs(n) < 8192 &&
          (!isDimension(axis) || n > 0);
      if (/^(x|y|width|height)$/.test(prop)) {
        const n = Number(value);
        if (sane(n, prop)) record.__lceFix73Original.set(clip + ":" + prop, n);
      }
      if (/^__lce_stage_(x|y|width|height)$/.test(prop)) {
        const axis = prop.substring("__lce_stage_".length);
        if (!sane(Number(value), axis)) {
          let original = Number(record.api.webIggyGet(clip, axis));
          if (!sane(original, axis)) original = record.__lceFix73Original.get(clip + ":" + axis);
          if (sane(original, axis)) {
            // Restore the actual Flash property as well as the native hitbox,
            // so the authored checkbox graphics and input share coordinates.
            try { record.api.webIggySet(clip, axis, original); } catch (_) {}
            const stage = Number(record.api.webIggyGet(clip, prop));
            value = sane(stage, axis) ? stage : original;
          }
        }
      }
    }
    setLast(value);
    return value;
  }'''
if src.count(old) != 1:
    raise SystemExit(f"Fix73 getValue anchor count {src.count(old)}; refusing unsafe patch")
src = src.replace(old, new, 1)

old = '''    key = String(key || "menu-help");
    const text = String(rawText || "").replace(/<[^>]*>/g, "").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/§./g, "").trim();'''
new = '''    key = String(key || "menu-help");
    // Fix73: the native C++ menu does not emit its scale9 draw request for
    // LaunchMoreOptionsMenu720.swf. The existing Console Edition panel painter
    // is used behind the original Ruffle checkboxes and labels. Bounds use
    // the observed native 1280x720 authored checkbox and section rectangles.
    if (key === "world-options" && visible &&
        /LaunchMoreOptionsMenu/i.test(String(record.filename || ""))) {
      setNativeScale9Panel(id, "world-options-main",
        Math.round(screenWidth * 396 / 1280),
        Math.round(screenHeight * 41 / 720),
        Math.round(screenWidth * 487 / 1280),
        Math.round(screenHeight * 450 / 720),
        screenWidth, screenHeight, 0, true);
    }
    const text = String(rawText || "").replace(/<[^>]*>/g, "").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/§./g, "").trim();'''
if src.count(old) != 1:
    raise SystemExit(f"Fix73 help anchor count {src.count(old)}; refusing unsafe patch")
src = src.replace(old, new, 1)
target.write_text(src, encoding="utf-8")
print(f"Fix73 options stage fallback and native scale9 panel installed: {target}")
