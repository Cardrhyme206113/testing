# Turkey OSM compression results

All candidates are rendered from the exact generated vector tiles over the same 3×3 z12 viewport in central Istanbul. The reference is a z14-capable Istanbul-only build with all building polygons and very light simplification.

| profile | PMTiles MiB | temp MBTiles MiB | Istanbul road vertices @z12 | vs ref | address points @z12 | building features @z12 |
|---|---:|---:|---:|---:|---:|---:|
| compact | 112.33 | 125.81 | 133131 | 57.0% | 15451 | 0 |
| lean | 154.22 | 175.75 | 195718 | 83.8% | 15451 | 0 |
| balanced | 263.23 | 291.69 | 163523 | 70.0% | 15451 | 0 |

## Notes

- `compact`: strongest geometry simplification; drops foot/path network and building polygons.
- `lean`: gentler z12 geometry, paths retained, building polygons dropped.
- `balanced`: z13 detail with named/addressed building polygons; mildest simplification.
- Address-bearing nodes/buildings are preserved as point features even when their building polygon is omitted.
- PMTiles uses gzip-compressed MVT tiles; OSM IDs/edit metadata are intentionally omitted.
