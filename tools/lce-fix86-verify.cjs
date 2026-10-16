'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT='fix86-frames';fs.mkdirSync(OUT,{recursive:true});
const pause=t=>new Promise(r=>setTimeout(r,t));
const cases=[[8,'HowToPlay'],[9,'ControllerLayout'],[11,'Credits'],[12,'CreateWorld'],[13,'WorldOptions']];
const result={cases:[],errors:[],started:new Date().toISOString()};
(async()=>{
const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
page.on('pageerror',e=>result.errors.push(String(e)));
page.on('crash',()=>result.errors.push('browser crashed'));
page.on('dialog',d=>d.dismiss().catch(()=>{}));
const state=()=>page.evaluate(()=>{const h=globalThis.LCEWebIggy;return {players:h.debugState().players.filter(x=>x.visible).map(x=>x.filename),overlays:[...document.querySelectorAll('canvas.lce-webiggy-scale9-panel-overlay')].filter(x=>getComputedStyle(x).visibility==='visible').map(x=>({key:x.dataset.lcePanelKey,rect:x.getBoundingClientRect().toJSON()})),clipPaths:[...document.querySelectorAll('ruffle-player')].map(x=>x.style.clipPath).filter(x=>x&&x!=='none'),root:h.debugState().root}});
const shot=n=>page.screenshot({path:path.join(OUT,n+'.png'),timeout:30000});
const open=async k=>{
const req=await page.evaluate(k=>Module._lce_web_test_open_ui(k),k);
if(req!==1)return req;let val=-99;for(let i=0;i<80;i++){await pause(220);val=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(val!==-99)break;}return val;
};
try{
await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
await pause(33500);
for(const [id,name] of cases){
let x;
if(id===13){
 const a=await open(12);await pause(1300);if(a!==1){result.cases.push({id,name,error:'CreateWorld failed '+a});continue;}
 await page.mouse.click(642,558);
 for(let i=0;i<80;i++){await pause(250);if((await state()).players.includes('LaunchMoreOptionsMenu720.swf'))break;}
 x=1;
}else x=await open(id);
await pause(1200);
const pre=await state();await shot(String(id).padStart(2,'0')+'-'+name+'-initial');
let later=null;
if(id===11){await pause(5500);later=await state();await shot('11-Credits-after-5s');}
if(id===13){await page.keyboard.press('ArrowDown');await pause(500);later=await state();await shot('13-WorldOptions-down');}
result.cases.push({id,name,opened:x,pre,later});
}
}catch(e){result.errors.push('HARNESS: '+String(e.stack||e));process.exitCode=2;}
finally{
 result.finished=new Date().toISOString();
 fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(result,null,2));
 console.log('FIX86_STATUS '+JSON.stringify({cases:result.cases.map(x=>({id:x.id,opened:x.opened,players:x.pre?.players,overlays:x.pre?.overlays?.map(z=>z.key),clipPaths:x.pre?.clipPaths})),errors:result.errors}).slice(0,21000));
 await browser.close().catch(()=>{});
}
})();
