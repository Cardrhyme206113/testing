'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT='fix80-gallery';fs.mkdirSync(OUT,{recursive:true});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const report={cases:[],errors:[],passed:false,at:new Date().toISOString()};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 page.on('pageerror',e=>report.errors.push(String(e)));page.on('crash',()=>report.errors.push('browser crash'));page.on('dialog',d=>d.dismiss().catch(()=>{}));
 const shot=async name=>page.screenshot({path:path.join(OUT,name+'.png'),timeout:30000});
 const open=async scene=>{
  const r=await page.evaluate(n=>Module._lce_web_test_open_ui(n),scene);
  if(r!==1)return r;let q=-99;
  for(let i=0;i<75;i++){await wait(220);q=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(q!==-99)break;}
  return q;
 };
 const details=()=>page.evaluate(()=>{
  const h=globalThis.LCEWebIggy;
  const player=h.debugState().players.filter(p=>p.visible&&/^Settings[A-Za-z]*Menu720[.]swf$/.test(p.filename)).sort((a,b)=>b.id-a.id)[0];
  if(!player)return {error:'no visible Settings menu',visible:h.debugState().players.filter(p=>p.visible).map(p=>p.filename)};
  const clip='BackgroundPanel';
  const c={exists:h.pathExists(player.id,clip),visible:h.getValue(player.id,clip,'visible')};
  for(const k of ['x','y','width','height'])c[k]=h.getValue(player.id,clip,'__lce_stage_'+k);
  const overlays=[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].filter(e=>getComputedStyle(e).visibility==='visible').map(e=>e.dataset.lcePanelKey);
  return {player:player.filename,id:player.id,background:c,overlays};
 });
 try{
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});await wait(34000);
  // scene=3 Options, 4 Audio, 5 Controls, 6 Graphics, 7 HUD settings.
  const items=[[3,'options'],[4,'audio'],[5,'control'],[6,'graphics'],[7,'interface']];
  for(const [scene,name] of items){
   const result=await open(scene);
   await wait(2000);
   const info=await details();
   await shot(String(scene)+'-'+name+'-fixed-frame');
   const expect=info.background?.exists === true;
   const repaired=expect && info.background?.visible === false && info.overlays?.includes('fix80-settings-nine-slice-frame');
   report.cases.push({scene,name,result,info,expect,repaired});
  }
  // Verify other screen did not regress by hiding its entire UI.
  const n=await open(12);await wait(1300);
  await shot('12-create-world-unchanged');
  const create=await page.evaluate(()=>(globalThis.LCEWebIggy?.debugState?.().players||[]).filter(p=>p.visible).map(p=>p.filename));
  report.create={result:n,visible:create};
  report.passed=report.cases.filter(c=>c.expect).length>=2 && report.cases.filter(c=>c.expect).every(c=>c.repaired) && report.errors.length===0 && create.some(x=>x==='CreateWorldMenu720.swf');
  if(!report.passed)process.exitCode=2;
 }catch(e){report.errors.push('HARNESS: '+String(e.stack||e));process.exitCode=2}
 finally{fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));console.log('FIX80_RESULT '+JSON.stringify(report).slice(0,26000));await browser.close().catch(()=>{})}
})();
