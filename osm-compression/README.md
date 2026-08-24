# Turkey OSM compression lab

Goal: turn the current Geofabrik Turkey `.osm.pbf` into a much smaller offline map while keeping useful search/display data and avoiding obvious geometry damage in dense areas such as central Istanbul.

The workflow builds three whole-Turkey candidates in parallel:

- **compact** — z3–12, strongest simplification, no building polygons, foot/path network omitted, addresses retained as points.
- **lean** — z3–12, gentler simplification, paths retained, no building polygons, addresses retained as points.
- **balanced** — z3–13, mild simplification, named/addressed building polygons at z13, addresses retained as points.

It also builds a much more detailed **reference** only for an Istanbul extract. Every candidate is rendered at z12 over the same 3×3 tile area around central Istanbul, and the workflow records per-layer feature/vertex counts and file sizes.

The final transport format is PMTiles. MBTiles is only kept temporarily so the workflow can decode and render the exact generated vector tiles for comparison.

Data: © OpenStreetMap contributors, ODbL 1.0. Turkey extract downloaded from Geofabrik when the workflow runs.
