#!/usr/bin/env python3
"""Package EXISTING native C++/WASM engine for static GitHub Pages hosting.

This deliberately DOES NOT claim to recompile engine code or remove compiled
native debug functions. It removes public profiling/testing assets and adds
a scoped COOP/COEP service worker for static hosting.
"""
from pathlib import Path
import shutil,sys,re,json,hashlib

src=Path(sys.argv[1]);dst=Path(sys.argv[2])
if dst.exists(): shutil.rmtree(dst)
dst.mkdir(parents=True)
# Deploy ONLY runtime artifacts: Fix110's archive also contains large native
# link archives, object files and CMake build directories (~300 MB of dead weight).
runtime_dirs={'vendor','ui-assets','audio-opus'}
runtime_files={'lce_web_demo.js','lce_web_demo.wasm','lce_web_demo.data',
               'lce_web_demo.worker.js'}
for path in src.iterdir():
    if path.is_dir():
        if path.name not in runtime_dirs: continue
        shutil.copytree(path,dst/path.name,ignore=shutil.ignore_patterns('*.map','.DS_Store'))
    elif path.name in runtime_files or path.suffix == '.swf':
        shutil.copy2(path,dst/path.name)
html=(src/'lce_web_demo.html').read_text(encoding='utf-8')
html=re.sub(r'html\.lce-debug-shell #emscripten_logo.*?#output\{display:block!important\}\s*','',html,flags=re.S)
html=re.sub(r"<script>if\(new URLSearchParams\(location.search\).has\('debugShell'\)\)\s*document.documentElement.classList.add\('lce-debug-shell'\);</script>\s*",'',html)
html=html.replace('<title>Emscripten-Generated Code</title>','<title>Minecraft Legacy Console Edition</title>')
bootstrap="""<script>
(function(){
  if(window.crossOriginIsolated||!('serviceWorker' in navigator))return;
  var k='lce-pages-coi-attempted-v1';
  navigator.serviceWorker.register('./coi-serviceworker.js',{scope:'./'})
  .then(function(){return navigator.serviceWorker.ready})
  .then(function(){
    if(!navigator.serviceWorker.controller&&sessionStorage.getItem(k)!=='1'){
      sessionStorage.setItem(k,'1');location.reload();
    }
    if(navigator.serviceWorker.controller&&!window.crossOriginIsolated&&sessionStorage.getItem(k)!=='2'){
      sessionStorage.setItem(k,'2');location.reload();
    }
  }).catch(function(e){console.error('[LCE] COOP/COEP setup failed',e)});
})();
</script>
"""
html=html.replace('</head>',bootstrap+'</head>')
(dst/'index.html').write_text(html,encoding='utf-8')
(dst/'.nojekyll').touch()
(dst/'coi-serviceworker.js').write_text("""/* Scoped to /lce/: do not affect other sites on the same GitHub Pages origin. */
self.addEventListener('install',e=>e.waitUntil(self.skipWaiting()));
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
self.addEventListener('fetch',e=>{
  if(e.request.method!=='GET'&&e.request.method!=='HEAD')return;
  if(new URL(e.request.url).origin!==self.location.origin)return;
  e.respondWith((async()=>{
    const response=await fetch(e.request);
    if(!response||response.type==='opaque'||response.type==='opaqueredirect')return response;
    const headers=new Headers(response.headers);
    headers.set('Cross-Origin-Opener-Policy','same-origin');
    headers.set('Cross-Origin-Embedder-Policy','require-corp');
    headers.set('Cross-Origin-Resource-Policy','same-origin');
    return new Response(response.body,{status:response.status,statusText:response.statusText,headers});
  })());
});
""",encoding='utf-8')
(dst/'RELEASE-NOTES.txt').write_text("""Existing native C++ -> Emscripten WASM build; not a fresh native recompile.
Native binary may contain internal debug and test hooks. External profiling
scripts and debug-shell entry have been excluded. This is a static Pages
distribution only.

Initial GitHub Pages visit may reload once to enable cross-origin isolation.
World saving/loading is currently stubbed in the C++ platform backend.
Do not rely on this build for persistent saves or world export/import.
Mouse slider dragging and context-sensitive Escape/back are NOT verified fixed.
Do not publish over an unrelated existing GitHub Pages site.
""",encoding='utf-8')
files=[p for p in dst.rglob('*') if p.is_file()]
manifest={'release':'Fix115-pages-distribution-existing-native-engine','engine':'original Fix110 C++ WebAssembly binary with Fix113 Ruffle cleanup','native_recompiled':False,'debug_tools':'external scripts omitted; internal hooks not stripped','files':len(files),'bytes':sum(p.stat().st_size for p in files),'sha256_wasm':hashlib.sha256((dst/'lce_web_demo.wasm').read_bytes()).hexdigest()}
(dst/'release-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('FIX115_PAGES_RELEASE',json.dumps(manifest))
