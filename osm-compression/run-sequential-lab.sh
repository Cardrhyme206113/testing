#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
OUT="$ROOT/osm-compression/run-output"
RESULTS="$ROOT/osm-compression/run-results"
mkdir -p "$OUT" "$RESULTS"

sudo apt-get update
sudo apt-get install -y osmium-tool
python3 -m pip install --disable-pip-version-check pillow mapbox-vector-tile

echo 'Downloading latest Turkey PBF...'
curl -fL --retry 6 --retry-delay 5 -o "$OUT/turkey.osm.pbf" \
  https://download.geofabrik.de/europe/turkey-latest.osm.pbf
osmium fileinfo -e "$OUT/turkey.osm.pbf" | tee "$RESULTS/source-info.txt"

# Dense stress-test region: broad central Istanbul, both sides of Bosphorus.
osmium extract --strategy=smart -b 28.75,40.80,29.35,41.25 \
  "$OUT/turkey.osm.pbf" -o "$OUT/istanbul.osm.pbf"
osmium fileinfo -e "$OUT/istanbul.osm.pbf" | tee "$RESULTS/istanbul-info.txt"

docker pull ghcr.io/systemed/tilemaker:master
docker pull protomaps/go-pmtiles:latest

build_profile() {
  local profile="$1"
  local input="$2"
  local building_mode="$3"
  local store="$OUT/store-$profile"
  mkdir -p "$store"

  python3 osm-compression/make_config.py "$profile" > "$OUT/config-$profile.json"
  sed -e "s/__PROFILE__/$profile/g" \
      -e "s/__BUILDING_MODE__/$building_mode/g" \
      osm-compression/process-template.lua > "$OUT/process-$profile.lua"

  docker run --rm -v "$ROOT:/data" -w /data \
    ghcr.io/systemed/tilemaker:master \
    --input "/data/${input#$ROOT/}" \
    --output "/data/osm-compression/run-output/$profile.mbtiles" \
    --config "/data/osm-compression/run-output/config-$profile.json" \
    --process "/data/osm-compression/run-output/process-$profile.lua" \
    --store "/data/osm-compression/run-output/store-$profile" \
    --threads 0

  python3 osm-compression/render_preview.py \
    "$OUT/$profile.mbtiles" \
    "$RESULTS/istanbul-$profile.png" \
    "$RESULTS/stats-$profile.json" \
    --profile "$profile" --zoom 12

  if [[ "$profile" != reference ]]; then
    docker run --rm -v "$ROOT:/data" protomaps/go-pmtiles:latest \
      convert "/data/osm-compression/run-output/$profile.mbtiles" \
              "/data/osm-compression/run-output/$profile.pmtiles"
    PROFILE="$profile" OUT="$OUT" RESULTS="$RESULTS" python3 - <<'PY'
import json, os
p=os.environ['PROFILE']; out=os.environ['OUT']; res=os.environ['RESULTS']
fn=f'{res}/stats-{p}.json'; s=json.load(open(fn))
s['pmtiles_bytes']=os.path.getsize(f'{out}/{p}.pmtiles')
s['pmtiles_mib']=round(s['pmtiles_bytes']/1048576,2)
s['mbtiles_mib']=round(s['mbtiles_bytes']/1048576,2)
json.dump(s,open(fn,'w'),indent=2)
PY
  fi
}

# First build the small, detailed reference. If our renderer/config has a problem,
# fail before spending time on all-Turkey candidates.
build_profile reference "$OUT/istanbul.osm.pbf" all

for p in compact lean balanced; do
  if [[ "$p" == balanced ]]; then bmode=named; else bmode=none; fi
  build_profile "$p" "$OUT/turkey.osm.pbf" "$bmode"
done

RESULTS="$RESULTS" python3 - <<'PY'
import glob, json, os
from PIL import Image
res=os.environ['RESULTS']
stats={}
for fn in glob.glob(f'{res}/stats-*.json'):
    s=json.load(open(fn)); stats[s['profile']]=s
ref=stats['reference']
def lv(s,l,k): return s.get('layers',{}).get(l,{}).get(k,0)
rows=[]
for p in ['compact','lean','balanced']:
    s=stats[p]
    rv=lv(s,'road','vertices'); rr=lv(ref,'road','vertices') or 1
    rows.append((p,s['pmtiles_mib'],s['mbtiles_mib'],rv,100*rv/rr,lv(s,'address','features'),lv(s,'building','features')))
lines=[
'# Turkey OSM compression results','',
'All candidates below are whole-Turkey builds. Visual/geometry comparison uses the same central-Istanbul 3×3 viewport at z12. The reference is an Istanbul-only z14-capable build with all buildings and very light simplification.','',
'| profile | PMTiles MiB | MBTiles MiB | Istanbul road vertices | vs reference | address points | building features @z12 |',
'|---|---:|---:|---:|---:|---:|---:|']
for r in rows:
    lines.append(f'| {r[0]} | {r[1]:.2f} | {r[2]:.2f} | {r[3]} | {r[4]:.1f}% | {r[5]} | {r[6]} |')
lines += ['', '## Profile meaning','',
'- **compact**: z3–12, strongest simplification, no foot/path network, no building polygons.',
'- **lean**: z3–12, gentler simplification, paths retained, no building polygons.',
'- **balanced**: z3–13, mild simplification, named/addressed building polygons at z13.',
'- All three preserve address-bearing nodes/buildings as compact point features even when building polygons are removed.',
'- OSM edit metadata and object IDs are intentionally not copied into the vector tiles.']
open(f'{res}/report.md','w').write('\n'.join(lines)+'\n')
json.dump(stats,open(f'{res}/stats-all.json','w'),indent=2)

names=['reference','compact','lean','balanced']
ims=[Image.open(f'{res}/istanbul-{n}.png').convert('RGB') for n in names]
w=max(i.width for i in ims); h=max(i.height for i in ims)
sheet=Image.new('RGB',(w*2,h*2),'white')
for idx,im in enumerate(ims): sheet.paste(im,((idx%2)*w,(idx//2)*h))
sheet.save(f'{res}/istanbul-comparison.png',optimize=True)
PY

echo '=== FINAL SIZES ==='
ls -lh "$OUT"/*.pmtiles
cat "$RESULTS/report.md"
