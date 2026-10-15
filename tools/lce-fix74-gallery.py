#!/usr/bin/env python3
"""Collect all actual Actions screenshot PNGs into a GitHub-browsable gallery."""
from pathlib import Path
from shutil import copy2
from html import escape
from urllib.parse import quote
import json

SOURCE = Path("evidence")
ROOT = Path("lce-gallery")
IMAGES = ROOT / "screens"
GROUPS = [
    ("fix70", "Fix70 — full UI scene/control matrix", "38748882722"),
    ("fix71", "Fix71 — native World Options and world-start inspection", "38758491351"),
    ("fix72", "Fix72 — menu state / inventory keyboard diagnosis", "38769392763"),
    ("fix73", "Fix73 — repaired More Options visual regression", "38772489669"),
]
ROOT.mkdir(exist_ok=True)
rows = []
for ident, label, run_id in GROUPS:
    src = SOURCE / ident
    items = []
    for p in sorted(src.rglob("*.png")):
        rel = p.relative_to(src)
        name = "__".join(rel.parts)
        destination = IMAGES / ident / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        copy2(p, destination)
        items.append({"name": p.name, "file": destination.relative_to(ROOT).as_posix()})
    rows.append({"id": ident, "label": label, "run_id": run_id, "screenshots": items})
    print(f"GALLERY: {ident} = {len(items)} screenshots")

(ROOT / "manifest.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
md = [
    "# Minecraft LCE Web — actual Chromium screenshots",
    "",
    "These are unmodified screenshots captured by the GitHub Actions browser runs.",
    "They are **not** reference art, and passing hitbox/geometry tests does not mean pixel-perfect UI fidelity.",
    "",
    "**Caution:** Fix70/71/72 are older runtime snapshots. Fix73 is the latest patched runtime and captured only one screenshot.",
    "",
    f"**Total: {sum(len(row['screenshots']) for row in rows)} screenshots.**",
    "",
    "For a full-screen responsive view, open [the offline gallery HTML](index.html) after cloning/downloading the branch.",
    "",
]
html_parts = ["""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LCE Web — screenshots</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#101318;color:#e4e7ec;font:14px system-ui,sans-serif}main{max-width:1500px;margin:auto;padding:24px}h1{font-size:25px}h2{font-size:19px;margin-top:32px}.note{color:#adb8c9}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(285px,1fr));gap:15px}.tile{display:block;color:inherit;text-decoration:none;border:1px solid #39424e;background:#19212a;padding:8px;border-radius:8px}.tile:hover{border-color:#a5caff}.tile img{width:100%;aspect-ratio:16/10;object-fit:contain;background:#050505}.name{overflow-wrap:anywhere;margin-top:7px}a{color:#9bc5fa}</style></head><body><main><h1>Minecraft LCE Web — Chromium screenshots</h1><p class="note">Real browser captures. Fix70/71/72 are older; Fix73 is the patched runtime. Click any screenshot to inspect the original.</p>"""]
for row in rows:
    md += [f"## {row['label']}", "", f"[GitHub Actions run](https://github.com/Cardrhyme206113/testing/actions/runs/{row['run_id']}) · {len(row['screenshots'])} screenshot(s)", ""]
    html_parts.append(f'<section><h2>{escape(row["label"])} — {len(row["screenshots"])} screenshot(s)</h2><div class="grid">')
    if not row["screenshots"]:
        md.extend(["*No screenshots captured in this Actions artifact.*", ""])
    else:
        for item in row["screenshots"]:
            rel = quote(item["file"], safe="/")
            name = escape(item["name"])
            md.append(f'<a href="{rel}"><img src="{rel}" width="300" alt="{name}"></a> ')
            html_parts.append(f'<a class="tile" href="{rel}" target="_blank"><img loading="lazy" src="{rel}" alt="{name}"><div class="name">{name}</div></a>')
        md.append("")
    html_parts.append("</div></section>")
html_parts.append("</main></body></html>")
(ROOT / "README.md").write_text("\n".join(md), encoding="utf-8")
(ROOT / "index.html").write_text("\n".join(html_parts), encoding="utf-8")
print(f"GALLERY_TOTAL={sum(len(row['screenshots']) for row in rows)}")
