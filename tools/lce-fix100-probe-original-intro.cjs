'use strict';
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const OUT='fix100-intro-probe';fs.mkdirSync(OUT,{recursive:true});
const pause=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const report={snapshots:[],errors:[]};
 page.on('pageerror',e=>report.errors.push(String(e)));
 page.on('dialog',d=>d.dismiss().catch(()=>{}));
 const state=()=>page.evaluate(()=>({players:(globalThis.LCEWebIggy?.debugState()?.players||[]).filter(x=>x.visible).map(x=>x.filename),status:document.getElementById('status')?.textContent}));
 try{
 await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
 await pause(34000);
 const req=await page.evaluate(()=>Module._lce_web_test_open_ui(1));
 report.req=req;
 for(let t=0;t<14000;t+=200){
   await pause(200);
   const s=await state();
   if(s.players.includes('Intro720.swf')){report.introSeen=true;break;}
 }
 for(const [name,ms] of [['02-original-intro-early',1200],['03-original-intro-mid',9000],['04-original-intro-late',11500]]){
  await pause(ms);
  const s=await state();
  await page.screenshot({path:path.join(OUT,name+'.png')});
  report.snapshots.push({name,scenes:s.players});
 }
 }catch(e){report.errors.push(String(e.stack||e));process.exitCode=1}
 finally{
  if(!report.introSeen){report.errors.push('Intro720.swf never appeared');process.exitCode=1}
  fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
  console.log('INTRO_PROBE '+JSON.stringify(report));
  await browser.close().catch(()=>{});
 }
})();