'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT='fix79-panel-probes';fs.mkdirSync(OUT,{recursive:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const report={probes:[],errors:[]},cases=[[12,'create-world'],[3,'settings-options'],[6,'settings-graphics'],[8,'how-to-play'],[10,'skin-select']];
const candidates=['BackgroundPanel','BackgroundPanel2','MainPanel','MainPanel.BackgroundPanel','MainPanel/BackgroundPanel','MainPanel\\x1fBackgroundPanel','Panel','Panel_2','WorldOptionsBox','FJ_Panel9Grid','HtmlBackground','ButtonListBackground'];
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 page.on('pageerror',e=>report.errors.push(String(e)));page.on('dialog',d=>d.dismiss().catch(()=>{}));
 await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
 await delay(33000);
 const open=async k=>{
  const r=await page.evaluate(k=>Module._lce_web_test_open_ui(k),k);if(r!==1)return r;
  for(let i=0;i<65;i++){await delay(220);let q=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(q!==-99)break;}
  await delay(850);return r;
 };
 const shot=async name=>page.screenshot({path:path.join(OUT,name+'.png'),timeout:30000});
 const find=async()=>page.evaluate(()=>{
   const a=globalThis.LCEWebIggy.debugState().players.filter(p=>p.visible&&String(p.filename||"").endsWith("Menu720.swf"));
   return a.sort((a,b)=>b.id-a.id).map(p=>({id:p.id,filename:p.filename}));
 });
 const probe=async(id,names)=>page.evaluate(({id,names})=>{
   const h=globalThis.LCEWebIggy,r=[];
   for(const name of names){
    const row={path:name,exists:false};
    try{row.exists=h.pathExists(id,name)}catch(e){row.pathError=String(e)}
    for(const prop of ['visible','x','y','width','height','__lce_stage_x','__lce_stage_y','__lce_stage_width','__lce_stage_height','alpha']){
     try {const v=h.getValue(id,name,prop);if(v!==undefined&&v!==null)row[prop]=v} catch(e){}
    }
    r.push(row);
   }
   return r;
 },{id,names});
 try{
  for(const [scene,name] of cases){
   await open(scene);await delay(950);
   const players=await find();const id=players[0]?.id;
   if(!id){report.errors.push('no current menu '+scene);continue;}
   await shot(scene+'-'+name+'-baseline');
   const paths=await probe(id,candidates);
   const entry={scene,name,players,id,paths,variants:[]};report.probes.push(entry);
   for(const p of paths){
    if(!p.exists || !['BackgroundPanel','BackgroundPanel2','MainPanel','Panel','Panel_2','WorldOptionsBox','HtmlBackground','ButtonListBackground'].includes(p.path))continue;
    let result=await page.evaluate(({id,name})=>{try{return globalThis.LCEWebIggy.setValue(id,name,'visible',false)}catch(e){return String(e)}},{id,name:p.path});
    await delay(450);
    await shot(scene+'-'+name+'-hide-'+p.path);
    entry.variants.push({path:p.path,hide:result,other:await page.evaluate(()=>({players:globalThis.LCEWebIggy.debugState().players.filter(p=>p.visible).map(p=>p.filename),overlays:[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].filter(x=>getComputedStyle(x).visibility==='visible').map(x=>x.dataset.lcePanelKey)}))});
    await page.evaluate(({id,name})=>{try{globalThis.LCEWebIggy.setValue(id,name,'visible',true)}catch(e){}},{id,name:p.path});
    await delay(250);
   }
  }
 }catch(e){report.errors.push(String(e.stack||e));}
 finally{
  fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
  console.log('FIX79_PANEL_REPORT '+JSON.stringify(report).slice(0,50000));
  await browser.close().catch(()=>{});
 }
})();
