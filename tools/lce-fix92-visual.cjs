'use strict';
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const OUT='fix92-review';fs.mkdirSync(OUT,{recursive:true});
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const report={screens:[],errors:[],states:{},testStarted:new Date().toISOString()};
(async()=>{
const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
page.on('pageerror',e=>report.errors.push(String(e.stack||e)));
page.on('crash',()=>report.errors.push('browser crashed'));
page.on('dialog',d=>d.dismiss().catch(()=>{}));
const screenshot=async name=>{await page.screenshot({path:path.join(OUT,name+'.png'),timeout:35000});report.screens.push(name+'.png')};
const open=async n=>{const req=await page.evaluate(n=>Module._lce_web_test_open_ui(n),n);if(req!==1)return req;let v=-99;for(let i=0;i<90;i++){await pause(200);v=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(v!==-99)break;}return v};
const detail=async()=>page.evaluate(()=>{
const h=globalThis.LCEWebIggy;
return {active:(h.debugState().players||[]).filter(x=>x.visible).map(x=>x.filename),
 overlays:[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].map(e=>({
  key:e.dataset.lcePanelKey,visible:getComputedStyle(e).visibility,
  stage:e.__lceStageRect||null
 })),creditsMasks:[...document.querySelectorAll('ruffle-player')].map(e=>e.style.clipPath).filter(Boolean)};
});
try{
 await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
 await pause(34000);
 let result=await open(12);
 if(result!==1)throw new Error('CreateWorld scene failed '+result);
 await pause(2200);
 report.states.create=await detail();await screenshot('01-create-world');
 // Open the actual texture pack list thumbnail without altering Flash controls.
 await page.mouse.click(495,449);await pause(3500);
 report.states.textureSelected=await detail();await screenshot('02-create-texture-selected');
 await page.keyboard.press('Escape');await pause(1500);
 result=await open(11);
 if(result!==1)throw new Error('Credits scene failed '+result);
 await pause(600);report.states.credits=await detail();
 await screenshot('03-credits-early');
 await pause(4900);await screenshot('04-credits-scrolling');
 await pause(7500);await screenshot('05-credits-later');
 // Confirm World Options survives separately.
 await page.keyboard.press('Escape');await pause(800);
 result=await open(12);await pause(1100);
 if(result!==1)throw new Error('CreateWorld reopen failed '+result);
 await page.mouse.click(641,558);
 for(let i=0;i<70;i++){await pause(220);let p=await detail();if(p.active.includes('LaunchMoreOptionsMenu720.swf'))break}
 await pause(900);report.states.moreOptions=await detail();await screenshot('06-world-options');
}catch(e){report.errors.push('HARNESS: '+String(e.stack||e));process.exitCode=1;}
finally{
report.finished=new Date().toISOString();fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
console.log('FIX92_VISUAL '+JSON.stringify({screens:report.screens,states:report.states,errors:report.errors}).slice(0,21000));
await browser.close().catch(()=>{});
}
})();
