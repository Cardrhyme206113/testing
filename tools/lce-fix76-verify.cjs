'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const {PNG}=require('pngjs');
const OUT='fix76-gallery';fs.mkdirSync(OUT,{recursive:true});
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const report={cases:[],errors:[],verdict:{},created:new Date().toISOString()};
const opts=['CheckboxPVP','CheckboxTrustSystem','CheckboxFireSpreads','CheckboxTNT','CheckboxHostPrivileges','CheckboxStructures','CheckboxFlatWorld','CheckboxBonusChest'];
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 page.on('pageerror',e=>report.errors.push(String(e)));
 page.on('crash',()=>report.errors.push('browser crashed'));
 page.on('dialog',d=>d.dismiss().catch(()=>{}));
 const shot=async name=>{
  const b=await page.screenshot({path:path.join(OUT,name+'.png'),timeout:30000});
  return PNG.sync.read(b);
 };
 const open=async k=>{
  const req=await page.evaluate(k=>Module._lce_web_test_open_ui(k),k);
  if(req!==1)return req;for(let i=0;i<60;i++){await pause(250);const n=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(n!==-99)return n;}return -99;
 };
 const labelsPresent=p=>{
  // Analyse only interior where the original checkbox labels should occur.
  // The unmodified Fix73 picture is flat RGB(204,204,204) with no textual
  // dark/light glyphs in this area. This is a coarse signal, not pixel parity.
  let changed=0,blackish=0,bright=0;
  for(let y=210;y<355;y++)for(let x=470;x<695;x++){
   const i=(y*p.width+x)*4;const [r,g,b]=[p.data[i],p.data[i+1],p.data[i+2]];
   if(Math.abs(r-g)>25||Math.abs(r-b)>25||Math.abs(g-b)>25)changed++;
   if(Math.max(r,g,b)<110)blackish++;
   if(r>190&&g>180&&b<120)bright++;
  }
  return {changed,blackish,bright};
 };
 try{
   await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
   await pause(34500);
   let status=await open(12);
   report.cases.push({name:'create',result:status});
   await pause(1000);
   await shot('01-create-world');
   await page.mouse.click(642,558);
   await pause(1500);
   let png=await shot('02-options-1.5s');
   report.verdict.first=labelsPresent(png);
   await pause(2800);
   png=await shot('03-options-4.3s');
   report.verdict.settled=labelsPresent(png);
   const controls=await page.evaluate(names=>names.map(name=>{
    const h=globalThis.LCEWebIggy;return {name,y:h.getValue(19,name,'y'),stage:h.getValue(19,name,'__lce_stage_y'),visible:h.getValue(19,name,'visible')};
   }),opts);
   report.verdict.controls=controls;
   report.verdict.players=await page.evaluate(()=>(globalThis.LCEWebIggy?.debugState?.().players||[]).filter(p=>p.visible).map(p=>p.filename));
   report.verdict.allVisible=controls.every(c=>Number.isFinite(Number(c.y))&&Number(c.y)>=0&&Number(c.y)<720&&c.visible===true);
   report.verdict.labelPixels=report.verdict.settled.blackish>200&&report.verdict.settled.changed>35;
   await page.keyboard.press('ArrowDown');await pause(1000);
   await shot('04-options-after-navigate');
   report.cases.push({name:'original-options',state:report.verdict});
   // Separate boot for basic menu regression and full-quality screenshots.
   const targets=[[0,'main-menu'],[1,'help-and-options'],[2,'settings'],[3,'settings-options'],[4,'settings-audio'],[5,'settings-control'],[6,'settings-graphics'],[7,'settings-UI'],[8,'how-to-play'],[9,'controller-layout'],[10,'skin-select']];
   for(const [k,name] of targets){
    const r=await open(k);await pause(800);
    report.cases.push({name,result:r});
    if(r===1)await shot('05-'+String(k).padStart(2,'0')+'-'+name);
   }
   report.verdict.pass=report.verdict.allVisible&&report.verdict.labelPixels&&report.errors.length===0;
   if(!report.verdict.pass)process.exitCode=1;
 }catch(e){report.errors.push('HARNESS: '+String(e.stack||e));process.exitCode=1;}
 finally{
  fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
  console.log('FIX76_RESULT '+JSON.stringify({passed:report.verdict.pass,allVisible:report.verdict.allVisible,labelPixels:report.verdict.labelPixels,first:report.verdict.first,settled:report.verdict.settled,controls:report.verdict.controls,cases:report.cases.map(x=>({name:x.name,result:x.result})),errors:report.errors}).slice(0,17000));
  await browser.close().catch(()=>{});
 }
})();
