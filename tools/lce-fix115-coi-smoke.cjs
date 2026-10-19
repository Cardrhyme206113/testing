'use strict';
const {chromium}=require('playwright'),fs=require('fs');
const f=fs.readFileSync('pages/index.html','utf8');
const m=f.match(/(<script>\s*\(function\(\)\{\s*if\(window\.crossOriginIsolated[\s\S]*?<\/script>)/);
if(!m)throw Error('COI bootstrap not found');
fs.writeFileSync('pages/coi-smoke.html','<!DOCTYPE html><html><head>'+m[1]+'</head><body>Service-worker bootstrap test</body></html>');
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  const page=await browser.newPage();
  const errors=[];page.on('pageerror',e=>errors.push(String(e)));
  try{
    await page.goto('http://127.0.0.1:8765/coi-smoke.html',{waitUntil:'domcontentloaded'});
    await page.waitForFunction(()=>crossOriginIsolated&&!!navigator.serviceWorker.controller,{timeout:20000});
    const result=await page.evaluate(()=>({
      isolated:crossOriginIsolated,
      controller:!!navigator.serviceWorker.controller,
      hostname:location.hostname,
      workers:typeof Worker !== 'undefined',
      sharedArrayBuffer:typeof SharedArrayBuffer !== 'undefined'
    }));
    if(!result.isolated||!result.controller||!result.sharedArrayBuffer)throw Error('isolation unavailable '+JSON.stringify(result));
    console.log('LCE_FIX115_ISOLATION_BROWSER_VERIFIED',JSON.stringify(result));
  }catch(e){console.error('LCE_FIX115_ISOLATION_TEST_FAILED',String(e.stack||e),errors);process.exitCode=1}
  finally{await browser.close();fs.unlinkSync('pages/coi-smoke.html')}
})();
