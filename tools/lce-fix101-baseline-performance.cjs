'use strict';
// Fix101: diagnostic only. Do not interpret the Fix96 artifact as a native Fix100 build.
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const out='fix101-evidence';fs.mkdirSync(out,{recursive:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800}});
 const errors=[],samples=[],scenesSeen=new Set();
 page.on('pageerror',e=>errors.push(String(e)));
 page.on('crash',()=>errors.push('Browser crashed'));
 try{
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
  await page.evaluate(()=>{window.__lceDt=[];let last=performance.now();function tick(t){window.__lceDt.push(t-last);if(window.__lceDt.length>1800)window.__lceDt.shift();last=t;requestAnimationFrame(tick)}requestAnimationFrame(tick)});
  for(let sec=5;sec<=90;sec+=5){
   await delay(5000);
   const sample=await page.evaluate(()=>{
    const scenes=(globalThis.LCEWebIggy?.debugState?.()?.players||[]).filter(x=>x.visible).map(x=>x.filename);
    const f=window.__lceDt.filter(x=>x>0).sort((a,b)=>a-b);
    const q=p=>f.length?f[Math.min(f.length-1,Math.floor(f.length*p))]:null;
    return {scenes,heapBytes:performance.memory?.usedJSHeapSize??null,frameMsP50:q(.5),frameMsP95:q(.95),frameMsP99:q(.99),isolated:crossOriginIsolated};
   });
   sample.scenes.forEach(s=>scenesSeen.add(s));
   samples.push({seconds:sec,...sample});
   if(sec===5||sec===90)await page.screenshot({path:path.join(out,'frame-'+sec+'s.png')});
  }
 }catch(e){errors.push(String(e.stack||e))}
 finally{
  const report={build:'Fix96 original binary baseline',nativeFix100Verified:false,note:'Fix100 C++ diff is only an unbuilt patch. This browser artifact cannot validate it.',scenesSeen:[...scenesSeen],samples,errors};
  fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2));
  console.log('FIX101_DIAGNOSTICS '+JSON.stringify({build:report.build,scenesSeen:report.scenesSeen,samples:samples.length,errors}));
  await browser.close().catch(()=>{});
  if(errors.length)process.exitCode=1;
 }
})();