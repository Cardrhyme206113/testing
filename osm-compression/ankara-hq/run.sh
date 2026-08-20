#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
BASE="$ROOT/osm-compression/ankara-hq"
OUT="$BASE/output"
RES="$BASE/results"
mkdir -p "$OUT" "$RES" "$OUT/store"

sudo apt-get update -qq
sudo apt-get install -y osmium-tool jq
python3 -m pip install --disable-pip-version-check pillow mapbox-vector-tile

echo '=== Download Turkey source ==='
curl -fL --retry 6 --retry-delay 5 -o "$OUT/turkey.osm.pbf" https://download.geofabrik.de/europe/turkey-latest.osm.pbf

# Build the actual Ankara admin_level=4 polygon from the OSM data itself instead of a hand-written bbox.
echo '=== Resolve Ankara Province boundary ==='
osmium tags-filter "$OUT/turkey.osm.pbf" r/name=Ankara -o "$OUT/ankara-name-relations.osm.pbf" --overwrite
osmium export "$OUT/ankara-name-relations.osm.pbf" --geometry-types=polygon -o "$OUT/ankara-candidates.geojson" --overwrite
python3 - "$OUT/ankara-candidates.geojson" "$OUT/ankara-boundary.geojson" <<'PY'
import json,sys
src,dst=sys.argv[1:]
data=json.load(open(src,encoding='utf-8'))

def coords(g):
    if isinstance(g,(int,float)): return
    if isinstance(g,list):
        if len(g)>=2 and all(isinstance(x,(int,float)) for x in g[:2]):
            yield g[0],g[1]
        else:
            for x in g: yield from coords(x)

def score(f):
    pts=list(coords((f.get('geometry') or {}).get('coordinates',[])))
    if not pts:return 0
    xs=[p[0] for p in pts]; ys=[p[1] for p in pts]
    return (max(xs)-min(xs))*(max(ys)-min(ys))

c=[]
for f in data.get('features',[]):
    p=f.get('properties') or {}
    g=(f.get('geometry') or {}).get('type')
    if p.get('name')=='Ankara' and str(p.get('admin_level',''))=='4' and p.get('boundary')=='administrative' and g in ('Polygon','MultiPolygon'):
        c.append(f)
if not c:
    print('Boundary candidates were:', file=sys.stderr)
    for f in data.get('features',[]): print(f.get('properties'), file=sys.stderr)
    raise SystemExit('Could not find Ankara admin_level=4 polygon')
f=max(c,key=score)
json.dump({'type':'FeatureCollection','features':[f]},open(dst,'w',encoding='utf-8'),ensure_ascii=False)
print('Selected boundary:',f.get('properties'))
PY

# Smart extraction keeps complete OSM objects/relations at the boundary. No coordinate or tag simplification here.
echo '=== Extract full Ankara Province, exact OSM objects ==='
osmium extract --strategy=smart -p "$OUT/ankara-boundary.geojson" "$OUT/turkey.osm.pbf" -o "$OUT/ankara-full.osm.pbf" --overwrite
osmium fileinfo -e "$OUT/ankara-full.osm.pbf" | tee "$RES/ankara-pbf-info.txt"

# Count matched named/building objects without pulling dependency nodes into the count files.
osmium tags-filter -R "$OUT/ankara-full.osm.pbf" nwr/name -o "$OUT/named-only.osm.pbf" --overwrite
osmium tags-filter -R "$OUT/ankara-full.osm.pbf" nwr/building -o "$OUT/buildings-only.osm.pbf" --overwrite
osmium tags-filter -R "$OUT/buildings-only.osm.pbf" nwr/name -o "$OUT/named-buildings-only.osm.pbf" --overwrite
osmium fileinfo -e "$OUT/named-only.osm.pbf" > "$RES/named-info.txt"
osmium fileinfo -e "$OUT/buildings-only.osm.pbf" > "$RES/buildings-info.txt"
osmium fileinfo -e "$OUT/named-buildings-only.osm.pbf" > "$RES/named-buildings-info.txt"

echo '=== Build Ankara HQ vector tiles ==='
docker pull ghcr.io/systemed/tilemaker:master
docker pull protomaps/go-pmtiles:latest
docker run --rm -v "$ROOT:/data" -w /data ghcr.io/systemed/tilemaker:master \
  --input /data/osm-compression/ankara-hq/output/ankara-full.osm.pbf \
  --output /data/osm-compression/ankara-hq/output/ankara-hq.mbtiles \
  --config /data/osm-compression/ankara-hq/config.json \
  --process /data/osm-compression/ankara-hq/process.lua \
  --store /data/osm-compression/ankara-hq/output/store \
  --threads 0

# Docker created the MBTiles as root; give it back to the runner before editing metadata.
sudo chown "$(id -u):$(id -g)" "$OUT/ankara-hq.mbtiles"
chmod u+rw "$OUT/ankara-hq.mbtiles"

# tilemaker can emit zero-area MBTiles bounds for polygon-clipped extracts.
# Derive the real Ankara Province bounds from the exact boundary GeoJSON before PMTiles conversion.
python3 - "$OUT/ankara-hq.mbtiles" "$OUT/ankara-boundary.geojson" <<'PY'
import json,sqlite3,sys
mb,gj=sys.argv[1:]
data=json.load(open(gj,encoding='utf-8'))
pts=[]
def walk(x):
    if isinstance(x,list):
        if len(x)>=2 and isinstance(x[0],(int,float)) and isinstance(x[1],(int,float)):
            pts.append((float(x[0]),float(x[1])))
        else:
            for y in x: walk(y)
for f in data.get('features',[]): walk((f.get('geometry') or {}).get('coordinates',[]))
if not pts: raise SystemExit('No Ankara boundary coordinates for MBTiles bounds')
xs=[p[0] for p in pts]; ys=[p[1] for p in pts]
minx,miny,maxx,maxy=min(xs),min(ys),max(xs),max(ys)
bounds=f'{minx:.7f},{miny:.7f},{maxx:.7f},{maxy:.7f}'
center=f'{(minx+maxx)/2:.7f},{(miny+maxy)/2:.7f},8'
db=sqlite3.connect(mb)
db.execute("DELETE FROM metadata WHERE name IN ('bounds','center')")
db.execute("INSERT INTO metadata(name,value) VALUES('bounds',?)",(bounds,))
db.execute("INSERT INTO metadata(name,value) VALUES('center',?)",(center,))
db.commit(); db.close()
print('Fixed MBTiles bounds:',bounds)
PY

docker run --rm -v "$ROOT:/data" protomaps/go-pmtiles:latest convert \
  /data/osm-compression/ankara-hq/output/ankara-hq.mbtiles \
  /data/osm-compression/ankara-hq/output/ankara-hq.pmtiles

# Dense central-Ankara checks: Kızılay/Çankaya at z15 and z16.
python3 osm-compression/render_preview.py "$OUT/ankara-hq.mbtiles" "$RES/ankara-z15.png" "$RES/ankara-z15.json" --profile ankara-hq --zoom 15 --lon 32.8543 --lat 39.9208 --radius 1
python3 osm-compression/render_preview.py "$OUT/ankara-hq.mbtiles" "$RES/ankara-z16.png" "$RES/ankara-z16.json" --profile ankara-hq --zoom 16 --lon 32.8543 --lat 39.9208 --radius 1

OUT="$OUT" RES="$RES" python3 - <<'PY'
import json,os,re
out,res=os.environ['OUT'],os.environ['RES']

def count(path,kind):
    t=open(path,encoding='utf-8').read()
    m=re.search(r'Number of '+kind+r':\s+(\d+)',t)
    return int(m.group(1)) if m else 0

def total_objs(path):
    return sum(count(path,k) for k in ('nodes','ways','relations'))

pbf=os.path.getsize(out+'/ankara-full.osm.pbf')
pm=os.path.getsize(out+'/ankara-hq.pmtiles')
mb=os.path.getsize(out+'/ankara-hq.mbtiles')
summary={
  'region':'Ankara Province, Türkiye (OSM admin_level=4 boundary)',
  'source_pbf_bytes':pbf,'source_pbf_mib':round(pbf/1048576,2),
  'hq_pmtiles_bytes':pm,'hq_pmtiles_mib':round(pm/1048576,2),
  'hq_mbtiles_bytes':mb,'hq_mbtiles_mib':round(mb/1048576,2),
  'maxzoom':16,'high_resolution':True,
  'named_osm_objects':total_objs(res+'/named-info.txt'),
  'building_osm_objects':total_objs(res+'/buildings-info.txt'),
  'named_building_osm_objects':total_objs(res+'/named-buildings-info.txt'),
  'name_fields_preserved':['name','name:tr','name:en','official_name','alt_name','short_name','loc_name','old_name','brand','operator','ref'],
  'notes':['ankara-full.osm.pbf keeps original OSM geometry, tags and normal snapshot metadata for selected Ankara objects','PMTiles keeps all buildings and common map/search properties; z16 has no configured geometry simplification, but vector-tile quantization still applies','lower zoom copies are simplified because that does not remove the exact high-zoom/source geometry']
}
json.dump(summary,open(res+'/summary.json','w'),indent=2,ensure_ascii=False)
open(res+'/summary.md','w').write(f'''# Ankara HQ OSM build\n\n- Exact Ankara PBF: **{summary['source_pbf_mib']:.2f} MiB**\n- HQ PMTiles: **{summary['hq_pmtiles_mib']:.2f} MiB**\n- MBTiles intermediate: **{summary['hq_mbtiles_mib']:.2f} MiB**\n- Named OSM objects: **{summary['named_osm_objects']:,}**\n- Building objects: **{summary['building_osm_objects']:,}**\n- Named building objects: **{summary['named_building_osm_objects']:,}**\n- Max zoom: **z16**, high-resolution vector tiles\n- All building polygons are kept. Primary, Turkish/English, alternate, official, local/old names plus brand/operator/ref are copied where present.\n\nThe PBF is the lossless OSM-object source for Ankara. The PMTiles file is a viewer/search product: exact-at-source and unsimplified at z16, but still subject to vector-tile coordinate quantization.\n''')
print(json.dumps(summary,indent=2,ensure_ascii=False))
PY

echo '=== FINAL ==='
ls -lh "$OUT/ankara-full.osm.pbf" "$OUT/ankara-hq.mbtiles" "$OUT/ankara-hq.pmtiles"
cat "$RES/summary.md"
