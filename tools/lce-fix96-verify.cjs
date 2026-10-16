'use strict';
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const OUT='fix96-review';fs.mkdirSync(OUT,{recursive:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));const failures=[];
(async()=>{
const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
page.on('pageerror',e=>failures.push('PAGE '+String(e.stack||e)));
page.on('crash',()=>failures.push('browser crashed'));
page.on('dialog',d=>d.dismiss().catch(()=>{}));
const state=()=>page.evaluate(()=>{
const h=globalThis.LCEWebIggy,p=(h?.debugState()?.players||[]).filter(x=>x.visible),
t=document.querySelector('.lce-webiggy-loading-native-tip'),
b=p.find(x=>x.filename==='Panorama720.swf'),
player=b && document.querySelector('[data-web-iggy-id="'+b.id+'"]');
const fallbackPlayer=b&&[...document.querySelectorAll('ruffle-player')].find(x=>x.dataset.webIggyId===String(b.id));
return {players:p.map(x=>x.filename),tip:t?.textContent||'',tipRect:t?.getBoundingClientRect().toJSON(),tipVisibility:t&&getComputedStyle(t).visibility,
panoramaFilter: fallbackPlayer && getComputedStyle(fallbackPlayer).filter,tipCount:document.querySelectorAll('.lce-webiggy-loading-native-tip').length,nativeCalls:globalThis.__lce96SeenNativeLabel||0,ticks:globalThis.__lce96RepairCalled||0,debug:globalThis.__lce96Debug||null};
});
let samples={};
try{
await page.goto('http://127.0.0.1:8000/lce_web_demo.html',{waitUntil:'domcontentloaded',timeout:120000});
await delay(34000);
samples.menu=await state();await page.screenshot({path:path.join(OUT,'01-title-blurred.png')});
await page.mouse.click(642,310);await delay(2200);
samples.create=await state();await page.screenshot({path:path.join(OUT,'02-create-world.png')});
await page.mouse.click(642,610);
await delay(1300);
samples.loading1=await state();await page.screenshot({path:path.join(OUT,'03-loading-tip-original.png')});
await delay(8300);
samples.loading2=await state();await page.screenshot({path:path.join(OUT,'04-loading-tip-next.png')});
if(samples.loading1.players.includes('FullscreenProgress720.swf')){
if(!samples.loading1.tip||samples.loading1.tip.length<18)failures.push('Missing native loading tip text');
if(samples.loading1.tipVisibility!=='visible')failures.push('Tip overlay not visible');
if(!samples.loading1.panoramaFilter?.includes('blur('))failures.push('Background panorama filter absent');
if(samples.loading2.players.includes('FullscreenProgress720.swf')&&samples.loading1.tip===samples.loading2.tip)failures.push('Native tip did not rotate across 7+ seconds');
}else failures.push('FullscreenProgress did not appear after normal Create New World navigation');
}catch(e){failures.push('HARNESS '+String(e.stack||e))}
finally{
fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify({samples,failures},null,2));
console.log('FIX96_VERIFY '+JSON.stringify({samples,failures}));
await browser.close().catch(()=>{});if(failures.length)process.exitCode=1;
}
})();