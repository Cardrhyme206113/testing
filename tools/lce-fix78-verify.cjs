'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright'),{PNG}=require('pngjs');
const OUT='fix78-review';fs.mkdirSync(OUT,{recursive:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const report={errors:[],controls:[],verified:false};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 page.on('pageerror',e=>report.errors.push(String(e)));
 page.on('dialog',d=>d.dismiss().catch(()=>{});
 const shot=async name=>PNG.sync.read(await page.screenshot({path:path.join(OUT,name+'.png')}));
 const state=()=>page.evaluate(()=>{
  const h=globalThis.LCEWebIggy,clips=['CheckboxPVP','CheckboxHostPrivileges','WorldOptions','CheckboxStructures','CheckboxFlatWorld','CheckboxBonusChest'];
  return {players:h.debugState().players.filter(x=>x.visible).map(x=>x.filename),
    controls:clips.map(name=>({name,y:h.getValue(19,name,'y'),sy:h.getValue(19,name,'__lce_stage_y'),shown:h.getValue(19,name,'visible')})),
    help:[...document.querySelectorAll('.lce-webiggy-menu-help-panel')].filter(x=>getComputedStyle(x).visibility==='visible').map(x=>x.dataset.lceText)};
 });
 try{
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});await delay(34000);
  const req=await page.evaluate(()=>Module._lce_web_test_open_ui(12));
  if(req!==1)throw new Error('Cannot open original Create World: '+req);
  for(let i=0;i<100;i++){await delay(180);if(await page.evaluate(()=>Module._lce_web_test_last_ui_result())!==-99)break;}
  await delay(1000);
  await page.mouse.click(642,558);
  for(let i=0;i<100;i++){await delay(300);if(await page.evaluate(()=>(globalThis.LCEWebIggy?.debugState?.().players||[]).some(x=>x.visible&&x.filename==='LaunchMoreOptionsMenu720.swf')))break;}
  await delay(3200);
  const b=await shot('01-repaired-options-before-interaction');
  report.before=await state();
  const positions=Object.fromEntries(report.before.controls.map(c=>[c.name,c.y]));
  report.geometry=positions.CheckboxStructures===381 && positions.CheckboxFlatWorld===415 && positions.CheckboxBonusChest===449;
  await page.mouse.click(459,435);await delay(900);
  const a=await shot('02-original-structures-checkbox-click');
  report.after=await state();
  let n=0;
  for(let y=421;y<=451;y++)for(let x=445;x<=475;x++){
   const i=(y*b.width+x)*4;
   for(let c=0;c<3;c++)if(Math.abs(b.data[i+c]-a.data[i+c])>30){n++;break;}
  }
  report.checkboxPixelChanges=n;
  report.helpChanged=JSON.stringify(report.before.help)!==JSON.stringify(report.after.help);
  report.verified=report.geometry && (n>30||report.helpChanged) && report.errors.length===0;
  if(!report.verified)process.exitCode=1;
 }catch(e){report.errors.push('HARNESS: '+String(e.stack||e));process.exitCode=1;}
 finally{fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));console.log('FIX78_RESULT '+JSON.stringify(report).slice(0,19000));await browser.close().catch(()=>{});}
})();
