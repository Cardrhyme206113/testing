#!/usr/bin/env python3
import json, sys

profile = sys.argv[1]
if profile not in {"compact", "lean", "balanced", "reference"}:
    raise SystemExit(f"unknown profile: {profile}")

P = {
    "compact": dict(maxzoom=12, road=0.00018, linear=0.00020, area=0.00028, land_min=11, poi_min=12, bld_min=13, combine=12),
    "lean": dict(maxzoom=12, road=0.00009, linear=0.00012, area=0.00016, land_min=10, poi_min=11, bld_min=13, combine=12),
    "balanced": dict(maxzoom=13, road=0.00005, linear=0.00007, area=0.00010, land_min=10, poi_min=11, bld_min=13, combine=13),
    # Only used on the Istanbul extract as a visual reference.
    "reference": dict(maxzoom=14, road=0.00001, linear=0.000015, area=0.00002, land_min=9, poi_min=10, bld_min=13, combine=10),
}[profile]

mz = P["maxzoom"]
def layer(minz, simplify=None, algorithm=None, combine_lines=None, combine_polygons=None, filter_below=None, filter_area=None):
    d = {"minzoom": minz, "maxzoom": mz}
    if simplify is not None:
        d.update({"simplify_below": mz + 1, "simplify_level": simplify, "simplify_ratio": 2.0})
    if algorithm:
        d["simplify_algorithm"] = algorithm
    if combine_lines is not None:
        d["combine_lines_below"] = combine_lines
    if combine_polygons is not None:
        d["combine_polygons_below"] = combine_polygons
    if filter_below is not None:
        d["filter_below"] = filter_below
    if filter_area is not None:
        d["filter_area"] = filter_area
    return d

layers = {
    "place": layer(3),
    "poi": layer(P["poi_min"]),
    "address": layer(12),
    "road": layer(5, P["road"], combine_lines=P["combine"]),
    "rail": layer(7, P["linear"], combine_lines=P["combine"]),
    "waterway": layer(7, P["linear"], combine_lines=P["combine"]),
    "coastline": layer(4, P["linear"], combine_lines=P["combine"]),
    "boundary": layer(3, P["linear"], combine_lines=P["combine"]),
    "water": layer(6, P["area"], algorithm="visvalingam", combine_polygons=P["combine"]),
    "landuse": layer(P["land_min"], P["area"], algorithm="visvalingam", combine_polygons=P["combine"],
                     filter_below=mz, filter_area=0.000002 if profile != "compact" else 0.000006),
    "building": layer(P["bld_min"], 0.000008 if profile != "reference" else None,
                      algorithm="buildings" if profile != "reference" else None),
}

config = {
    "layers": layers,
    "settings": {
        "minzoom": 3,
        "maxzoom": mz,
        "basezoom": mz,
        "include_ids": False,
        "high_resolution": False,
        "compress": "gzip",
        "name": f"Turkey compact OSM - {profile}",
        "version": "0.1",
        "description": "Purpose-built compact offline OSM vector tiles; ODbL/OpenStreetMap contributors",
        "metadata": {
            "attribution": "© OpenStreetMap contributors",
            "license": "ODbL 1.0",
            "profile": profile,
        },
    },
}

json.dump(config, sys.stdout, separators=(",", ":"))
