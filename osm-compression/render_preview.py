#!/usr/bin/env python3
import argparse, gzip, json, math, os, sqlite3, zlib
from collections import defaultdict
from PIL import Image, ImageDraw, ImageFont
import mapbox_vector_tile

LAYER_ORDER = ["landuse", "water", "coastline", "boundary", "waterway", "rail", "road", "building", "poi", "place", "address"]

def lonlat_to_tile(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    latr = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(latr)) / math.pi) / 2.0 * n)
    return x, y

def load_tile(db, z, x, y):
    tms_y = (1 << z) - 1 - y
    row = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (z, x, tms_y)).fetchone()
    if not row:
        return {}
    raw = row[0]
    if raw[:2] == b"\x1f\x8b": raw = gzip.decompress(raw)
    elif raw[:1] == b"x":
        try: raw = zlib.decompress(raw)
        except Exception: pass
    return mapbox_vector_tile.decode(raw, default_options={"geojson": False, "y_coord_down": True})

def iter_points(coords):
    if not coords: return
    if isinstance(coords[0], (int, float)):
        yield coords
    else:
        for c in coords:
            yield from iter_points(c)

def vertex_count(coords):
    return sum(1 for _ in iter_points(coords))

def draw_geom(draw, geom, offx, offy, scale, layer, props):
    typ, coords = geom.get("type"), geom.get("coordinates", [])
    def pt(p): return (offx + p[0] * scale, offy + p[1] * scale)
    if layer == "water": fill, outline = "#d6ecff", "#77a9d4"
    elif layer == "landuse": fill, outline = "#e1ecd6", "#b5c5a2"
    elif layer == "building": fill, outline = "#d8d2cb", "#aaa29a"
    else: fill = outline = None

    if typ == "Point":
        x, y = pt(coords); r = 2
        color = "#8c3fc7" if layer == "poi" else "#111111"
        draw.ellipse((x-r, y-r, x+r, y+r), fill=color)
    elif typ == "MultiPoint":
        for p in coords: draw_geom(draw, {"type":"Point","coordinates":p}, offx, offy, scale, layer, props)
    elif typ == "LineString":
        pts = [pt(p) for p in coords]
        if len(pts) < 2: return
        if layer == "road":
            cls = props.get("class", "other")
            widths = {"motorway":4,"trunk":4,"primary":3,"secondary":3,"tertiary":2,"minor":1.5,"service":1,"path":1,"other":1}
            colors = {"motorway":"#db7b55","trunk":"#dc9860","primary":"#d6aa66","secondary":"#c3aa82","tertiary":"#999999","minor":"#777777","service":"#999999","path":"#aaa27f","other":"#999999"}
            draw.line(pts, fill=colors.get(cls,"#888888"), width=max(1,int(widths.get(cls,1))), joint="curve")
        elif layer == "rail": draw.line(pts, fill="#735b78", width=2)
        elif layer == "waterway": draw.line(pts, fill="#6f9fc8", width=2)
        elif layer == "coastline": draw.line(pts, fill="#4d799f", width=2)
        elif layer == "boundary": draw.line(pts, fill="#9c6e9c", width=1)
    elif typ == "MultiLineString":
        for line in coords: draw_geom(draw, {"type":"LineString","coordinates":line}, offx, offy, scale, layer, props)
    elif typ == "Polygon":
        for i, ring in enumerate(coords):
            pts = [pt(p) for p in ring]
            if len(pts) >= 3:
                if i == 0: draw.polygon(pts, fill=fill, outline=outline)
                else: draw.line(pts + [pts[0]], fill=outline or "#ffffff", width=1)
    elif typ == "MultiPolygon":
        for poly in coords: draw_geom(draw, {"type":"Polygon","coordinates":poly}, offx, offy, scale, layer, props)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mbtiles")
    ap.add_argument("png")
    ap.add_argument("stats")
    ap.add_argument("--profile", required=True)
    ap.add_argument("--zoom", type=int, default=12)
    ap.add_argument("--lon", type=float, default=28.9784)
    ap.add_argument("--lat", type=float, default=41.0332)
    ap.add_argument("--radius", type=int, default=1)
    args = ap.parse_args()

    tile_px = 320
    center_x, center_y = lonlat_to_tile(args.lon, args.lat, args.zoom)
    side = args.radius * 2 + 1
    im = Image.new("RGB", (tile_px * side, tile_px * side + 36), "#f5f3ee")
    draw = ImageDraw.Draw(im)
    stats = {"profile":args.profile, "zoom":args.zoom, "center":[args.lon,args.lat], "layers":{}, "tiles_present":0}
    layer_counts = defaultdict(lambda: {"features":0,"vertices":0})

    db = sqlite3.connect(args.mbtiles)
    for gy, ty in enumerate(range(center_y-args.radius, center_y+args.radius+1)):
        for gx, tx in enumerate(range(center_x-args.radius, center_x+args.radius+1)):
            decoded = load_tile(db, args.zoom, tx, ty)
            if decoded: stats["tiles_present"] += 1
            ox, oy = gx * tile_px, gy * tile_px
            draw.rectangle((ox,oy,ox+tile_px,oy+tile_px), outline="#d0cdc6")
            for layer in LAYER_ORDER:
                ld = decoded.get(layer)
                if not ld: continue
                extent = ld.get("extent", 4096)
                scale = tile_px / extent
                for feat in ld.get("features", []):
                    geom = feat.get("geometry") or {}
                    props = feat.get("properties") or {}
                    layer_counts[layer]["features"] += 1
                    layer_counts[layer]["vertices"] += vertex_count(geom.get("coordinates", []))
                    draw_geom(draw, geom, ox, oy, scale, layer, props)
                    if layer in ("place", "poi") and props.get("name"):
                        pts = list(iter_points(geom.get("coordinates", [])))
                        if pts:
                            x,y = ox+pts[0][0]*scale+3, oy+pts[0][1]*scale-5
                            draw.text((x,y), str(props["name"])[:28], fill="#202020")
    db.close()

    stats["layers"] = dict(layer_counts)
    stats["mbtiles_bytes"] = os.path.getsize(args.mbtiles)
    draw.rectangle((0, tile_px*side, tile_px*side, tile_px*side+36), fill="#ffffff")
    draw.text((10,tile_px*side+10), f"{args.profile} | Istanbul center | z{args.zoom} | 3×3 tiles", fill="#111111")
    im.save(args.png, optimize=True)
    with open(args.stats,"w",encoding="utf-8") as f: json.dump(stats,f,indent=2,ensure_ascii=False)

if __name__ == "__main__": main()
