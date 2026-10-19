'use strict';
const {chromium}=require('playwright'),fs=require('fs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1280,height:800}});
  const failures=[],reports=[];
  page.on('pageerror',e=>{if(!String(e.message).includes('Incorrect locale information provided'))failures.push('pageerror: '+e.message)});
  page.on('crash',()=>failures.push('page crash'));
  let movieRequests=0;
  page.on('request',r=>{if(/\/Controls720\.swf$/.test(r.url()))movieRequests++});
  try {
    await page.goto('http://127.0.0.1:8765/?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:45000});
    await page.waitForFunction(()=>crossOriginIsolated&&!!navigator.serviceWorker.controller,{timeout:25000});
    reports.push({phase:'crossOriginIsolation',...await page.evaluate(()=>({isolated:crossOriginIsolated,sharedArrayBuffer:typeof SharedArrayBuffer==='function',worker:!!navigator.serviceWorker.controller}))});
    await page.waitForFunction(()=>globalThis.LCEWebIggy?.debugState?.()?.players?.some(x=>x.visible&&/MainMenu720.swf/.test(x.filename)),{timeout:110000});
    reports.push({phase:'realMainMenu',...await page.evaluate(()=>({scenes:globalThis.LCEWebIggy.debugState().players.filter(x=>x.visible).map(x=>x.filename),hasNativeHook:typeof Module?._lce_web_test_open_ui==='function',cachedAsset:false}))});
    if(!(await page.evaluate(()=>typeof Module?._lce_web_test_open_ui==='function')))throw Error('native test hook absent; cannot verify original controls menu');
    const navRequest=await page.evaluate(()=>Module._lce_web_test_open_ui(9));
    reports.push({phase:'nativeControlsRequest',request:navRequest});
    await page.waitForFunction(()=>globalThis.LCEWebIggy?.debugState?.()?.players?.some(x=>x.visible&&/Controls720.swf/.test(x.filename)) ,undefined,{timeout:55000});
    const state=await page.evaluate(()=>({scenes:globalThis.LCEWebIggy.debugState().players.filter(x=>x.visible).map(x=>x.filename),menuVisible:globalThis.LCEWebIggy.debugState().menuVisible}));
    reports.push({phase:'nativeControls',...state});
    await page.screenshot({path:'real-fix116-controls.png',timeout:10000});
    await sleep(1500);
    reports.push({phase:'controlAssets',requestCount:movieRequests});
    if(movieRequests>1)failures.push('Controls SWF requested >1 time despite movie promise caching');
    if(!state.scenes.includes('Controls720.swf'))failures.push('Native Controls movie missing');
    if(!reports[0].sharedArrayBuffer)failures.push('No SharedArrayBuffer support on static site');
    console.log('FIX116_REAL_PAGES_UI',JSON.stringify({reports,failures}));
  }catch(e){failures.push(String(e.stack||e));console.log('FIX116_REAL_PAGES_FAILURE',JSON.stringify({reports,failures}))}
  finally{await browser.close(); if(failures.length)process.exitCode=1}
})();
