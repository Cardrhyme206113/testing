'use strict';
// Visual and functional smoke tests for the original SWF UI plus fix67 native
// panels. Use the actual browser build and original C++ scene constructors.
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT=path.resolve(process.env.UI_TEST_OUT || 'fix67-visual-results');
fs.mkdirSync(OUT,{recursive:true});
const pause=t=>new Promise(r=>setTimeout(r,t));
const report={started:new Date().toISOString(),cases:[],pageErrors:[],browserConsole:[],badRequests:[]};
(async()=>{
const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
const context=await browser.newContext({viewport:{width:1280,height:800},locale:'en-US',deviceScaleFactor:1});
let page;
async function start(){
 if(page)await page.close();
 page=await context.newPage();
 page.on('pageerror',e=>report.pageErrors.push(String(e.stack||e).slice(0,4000)));
 page.on('crash',()=>report.pageErrors.push('browser crash'));
 page.on('console',m=>{if(report.browserConsole.length<3000)report.browserConsole.push({type:m.type(),text:m.text().slice(0,850)});});
 page.on('requestfailed',r=>report.badRequests.push({url:r.url(),reason:r.failure()?.errorText}));
 page.on('dialog',d=>d.dismiss().catch(()=>{}));
 await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
 await pause(32000);
 const shell=await page.evaluate(()=>({
   title:document.title,
   debugLogo:document.getElementById('emscripten_logo')?getComputedStyle(document.getElementById('emscripten_logo')).display:'absent',
   canvasRect:(()=>{const r=document.getElementById('canvas').getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}})(),
   root:globalThis.LCEWebIggy?.debugState?.().root,
   isolation:crossOriginIsolated,
 }));
 report.cases.push({name:'shell',shell,pass:shell.debugLogo==='none'&&shell.canvasRect.y<60});
}
async function open(k){
 const req=await page.evaluate(k=>Module._lce_web_test_open_ui(k),k);if(req!==1)return {req,result:req};
 let result=-99;for(let i=0;i<90;i++){await pause(200);result=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(result!==-99)break;}
 await pause(1500);return {req,result};
}
async function shot(name){const n=name+'.png';await page.screenshot({path:path.join(OUT,n),timeout:40000});return n;}
async function state(){return page.evaluate(()=>({
  players:globalThis.LCEWebIggy?.debugState?.().players?.filter(x=>x.visible).map(x=>x.filename)||[],
  panels:[...document.querySelectorAll('canvas.lce-webiggy-menu-help-panel')].filter(e=>getComputedStyle(e).visibility==='visible').map(e=>({text:e.dataset.lceText,x:e.getBoundingClientRect().x,y:e.getBoundingClientRect().y,w:e.getBoundingClientRect().width,h:e.getBoundingClientRect().height})),
  scale9:[...document.querySelectorAll('canvas.lce-webiggy-scale9-panel-overlay')].filter(e=>getComputedStyle(e).visibility==='visible').map(e=>({key:e.dataset.lcePanelKey}))
}));}
async function stageClick(sx,sy){
 const root=await page.evaluate(()=>globalThis.LCEWebIggy?.debugState?.().root);
 if(!root)throw new Error('No UI root');
 await page.mouse.click(root.x+sx*(root.width/1280),root.y+sy*(root.height/720));
}
try {
 await start();
 let r=await open(3);let v=await state();await shot('01-settings-options');
 report.cases.push({name:'settings-options',open:r,visual:v,pass:r.result===1&&v.panels.some(x=>x.text.length>12)});
 await stageClick(640,441);await pause(1200);
 let next=await state();await shot('02-settings-difficulty');
 report.cases.push({name:'settings-difficulty-click',visual:next,textChanged:next.panels[0]?.text!==v.panels[0]?.text});
 await start();r=await open(12);await shot('03-create-world');
 await stageClick(640,517);await pause(2200);
 v=await state();await shot('04-more-options');
 report.cases.push({name:'more-options',open:r,visual:v,pass:v.panels.some(x=>x.text.length>8)});
 await stageClick(455,84);await pause(1200);
 next=await state();await shot('05-more-options-toggle');
 report.cases.push({name:'more-options-toggle',visual:next,textChanged:next.panels[0]?.text!==v.panels[0]?.text});
 await page.keyboard.press('Escape');await pause(1800);await shot('06-return-create-world');
 await start();r=await open(0);v=await state();await shot('07-mainmenu');
 report.cases.push({name:'main-menu',open:r,visual:v,pass:r.result===1});
 // Optional original native Help & Options navigation still works.
 r=await open(1);await shot('08-help-and-options');
 report.cases.push({name:'help-options',open:r,pass:r.result===1});
} catch(e){report.pageErrors.push('HARNESS: '+String(e.stack||e));process.exitCode=1;}
finally{
 report.finished=new Date().toISOString();
 fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
 fs.writeFileSync(path.join(OUT,'summary.txt'),JSON.stringify({cases:report.cases.map(x=>({name:x.name,open:x.open,pass:x.pass,panels:x.visual?.panels?.map(y=>y.text),scale9:x.visual?.scale9})),pageErrors:report.pageErrors,badRequests:report.badRequests,console:report.browserConsole.filter(x=>/warning|error|RangeError|failed|exception/i.test(x.text)).slice(-65)},null,2));
 await browser.close();
}
})().catch(e=>{console.error(e);process.exitCode=1});