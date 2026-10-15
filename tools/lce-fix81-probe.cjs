'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright'),OUT='fix81-nested-probe';
fs.mkdirSync(OUT,{recursive:true});
const wait=t=>new Promise(r=>setTimeout(r,t));
const report={paths:[],properties:{},errors:[]};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 page.on('pageerror',e=>report.errors.push(String(e)));
 try{
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
  await wait(33500);
  const req=await page.evaluate(()=>Module._lce_web_test_open_ui(12));
  if(req!==1)throw Error('CreateWorld not available');
  for(let i=0;i<60;i++){await wait(220);if((await page.evaluate(()=>Module._lce_web_test_last_ui_result()))!==-99)break;}
  await wait(1800);await page.screenshot({path:path.join(OUT,'baseline.png')});
  const d=await page.evaluate(()=>{
   const h=globalThis.LCEWebIggy;
   const p=h.debugState().players.filter(x=>x.visible&&x.filename==='CreateWorldMenu720.swf')[0];
   if(!p)return {error:'CreateWorld SWF missing'};
   const id=p.id;
   const sub=['BackgroundPanel','Panel_2','FJ_Panel9Grid','MainPanel','MainPanel.Panel_2','MainPanel.BackgroundPanel','MainPanel.FJ_Panel9Grid','Panel_2.BackgroundPanel'];
   const names=new Set(sub);
   const parts=['MainPanel','Panel_2','BackgroundPanel','FJ_Panel9Grid','m_mcFadePanel'];
   for(const a of parts)for(const b of parts)if(a!==b)for(const sep of ['.','/',':','\\','\u001f','\u0000','->'])names.add(a+sep+b);
   const rows=[];
   for(const q of names){
    let exists=false;
    try{exists=h.pathExists(id,q)}catch(e){}
    if(exists){
      const row={path:q};
      for(const k of ['visible','x','y','width','height','alpha','_visible','_alpha']){
       try{const v=h.getValue(id,q,k); if(v!==undefined&&v!==null) row[k]=String(v)}catch(e){}
      }
      rows.push(row);
    }
   }
   const props={};
   for(const property of ['BackgroundPanel','Panel_2','FJ_Panel9Grid','scale9Grid','numChildren','__lce_children','visible','alpha','_alpha']){
    try{const v=h.getValue(id,'MainPanel',property);props[property]=v===undefined?'undefined':String(v)}catch(e){props[property]='error:'+String(e)}
   }
   return {rows,props,id};
  });
  report.paths=d.rows;report.properties=d.props;report.id=d.id;report.error=d.error;
  for(const entry of d.rows||[]){
    if(entry.path==='MainPanel')continue;
    const p=entry.path;
    await page.evaluate(({id,p})=>globalThis.LCEWebIggy.setValue(id,p,'visible',false),{id:d.id,p});
    await wait(350);await page.screenshot({path:path.join(OUT,'hide-'+p.replace(/[^A-Za-z0-9_.-]/g,'_')+'.png')});
    await page.evaluate(({id,p})=>globalThis.LCEWebIggy.setValue(id,p,'visible',true),{id:d.id,p});
  }
 }catch(e){report.errors.push(String(e.stack||e));}
 finally{
  fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
  console.log('FIX81_NESTED '+JSON.stringify(report).slice(0,24000));await browser.close().catch(()=>{});
 }
})();
