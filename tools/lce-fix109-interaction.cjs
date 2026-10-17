'use strict';
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const sleep=ms=>new Promise(r=>setTimeout(r,ms)),dir='fix109-interactions';fs.mkdirSync(dir,{recursive:true});
(async()=>{
 const b=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await b.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const r={build:'Fix109 native Flash playable',actions:[],exceptions:[],fatal:[],worldReady:false};
 page.on('pageerror',e=>r.exceptions.push(String(e.stack||e)));page.on('crash',()=>r.fatal.push('PAGE CRASH'));
 const state=()=>page.evaluate(()=>({
   scenes:(globalThis.LCEWebIggy?.debugState?.()?.players||[]).filter(x=>x.visible).map(x=>x.filename),
   last:typeof Module?._lce_web_test_last_ui_result==='function'?Module._lce_web_test_last_ui_result():null,
   ready:typeof Module?._lce_web_test_open_ui==='function',
   loaded:(globalThis.__lceFix109SwfLoads||[]).map(x=>x.name)
 }));
 const open=async(id,name)=>{
  const q=await page.evaluate(id=>Module._lce_web_test_open_ui(id),id);
  let s;for(let i=0;i<100;i++){await sleep(250);s=await state();if(s.scenes.includes(name)&&s.last===1)break}
  r.actions.push({operation:'open',id,queue:q,...s});
  if(!s?.scenes.includes(name)||s.last!==1)throw Error('Could not open '+name+' '+JSON.stringify(s));
  await sleep(700);
 };
 const snap=async(name)=>page.screenshot({path:path.join(dir,name+'.png')});
 try {
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
  let started=false;
  for(let i=0;i<90;i++){await sleep(1000);if((await state()).ready){started=true;break}}
  if(!started)throw Error('WebIggy API not ready');
  await open(9,'Controls720.swf');
  await snap('01-controls-before');
  await page.mouse.click(642,294);await sleep(600);
  r.actions.push({operation:'click-layout2',...(await state())});await snap('02-controls-layout2');
  await page.mouse.click(786,294);await sleep(600);
  r.actions.push({operation:'click-layout3',...(await state())});await snap('03-controls-layout3');
  await open(12,'CreateWorldMenu720.swf');
  await snap('04-createworld-before');
  await page.mouse.click(488,444);await sleep(1100);
  r.actions.push({operation:'click-texturepack',...(await state())});await snap('05-texturepack-interacted');
  await open(12,'CreateWorldMenu720.swf');
  await page.mouse.click(640,605);
  r.actions.push({operation:'click-create-new-world',...(await state())});
  let s;for(let t=0;t<150;t++){
   await sleep(1000);s=await state();
   if(t%30===0){r.actions.push({operation:'generation-check',t,...s});await snap('06-generation-'+t)}
   if(t>8&&!s.scenes.includes('CreateWorldMenu720.swf')) {
     const q=await page.evaluate(()=>Module._lce_web_test_open_ui(20));
     if(q===1){await sleep(1000);const ready=await state();
       if(ready.last===1){r.worldReady=true;r.actions.push({operation:'world-ready',t,...ready});break;}
     }
   }
  }
  if(!r.worldReady)r.fatal.push('world not ready after 150 seconds');
  else {
   await page.keyboard.press('Backquote');await sleep(400);
   await page.keyboard.press('KeyW');await page.keyboard.press('Space');
   await snap('07-gameplay');
  }
 }catch(e){r.fatal.push(String(e.stack||e))}
 finally {
  fs.writeFileSync(path.join(dir,'report.json'),JSON.stringify(r,null,2));
  console.log('FIX109_INTERACTION_RESULT '+JSON.stringify({worldReady:r.worldReady,actions:r.actions.length,errors:r.exceptions,fatal:r.fatal}));
  await b.close().catch(()=>{});
  if(r.exceptions.length||r.fatal.length)process.exitCode=1;
 }
})();