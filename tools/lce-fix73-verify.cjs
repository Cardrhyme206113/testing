'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT=process.env.UI_TEST_OUT||'fix73-evidence';fs.mkdirSync(OUT,{recursive:true});
const sleep=t=>new Promise(r=>setTimeout(r,t));
const report={started:new Date().toISOString(),errors:[],controls:[],probes:[],panels:[],passed:false};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const events=[];
 page.on('console',m=>{if(events.length<5000)events.push(m.text());});
 page.on('pageerror',e=>report.errors.push(String(e)));
 page.on('crash',()=>report.errors.push('browser crashed'));
 page.on('dialog',d=>d.dismiss().catch(()=>{}));
 try{
   await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
   await sleep(33000);
   const request=await page.evaluate(()=>Module._lce_web_test_open_ui(12));
   if(request!==1)throw new Error('Could not open original Create World: '+request);
   for(let k=0;k<60;k++){await sleep(200);if(await page.evaluate(()=>Module._lce_web_test_last_ui_result())!==-99)break;}
   await sleep(1400);
   await page.mouse.click(642,558);
   for(let i=0;i<75;i++){
     await sleep(250);
     const loaded=await page.evaluate(()=>(globalThis.LCEWebIggy?.debugState?.().players||[]).some(p=>p.visible&&p.filename.includes('LaunchMoreOptionsMenu')));
     if(loaded)break;
   }
   await sleep(2500);
   await page.screenshot({path:path.join(OUT,'01-fix73-more-options.png'),timeout:30000});
   const state=await page.evaluate(()=>{
     const options=globalThis.LCEWebIggy?.debugState?.().players?.filter(p=>p.visible).map(p=>p.filename)||[];
     const panels=[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].map(e=>({key:e.dataset.lcePanelKey,visibility:getComputedStyle(e).visibility}));
     const p=['CheckboxPVP','CheckboxTrustSystem','CheckboxFireSpreads','CheckboxTNT','CheckboxHostPrivileges','CheckboxFlatWorld','CheckboxBonusChest'];
     const probes=p.map(name=>({name,y:globalThis.LCEWebIggy.getValue(19,name,'__lce_stage_y'),height:globalThis.LCEWebIggy.getValue(19,name,'__lce_stage_height'),localY:globalThis.LCEWebIggy.getValue(19,name,'y')}));
     const help=[...document.querySelectorAll('.lce-webiggy-menu-help-panel')].filter(e=>getComputedStyle(e).visibility==='visible').map(e=>e.dataset.lceText);
     return {players:options,panels,probes,help};
   });
   report.panels=state.panels;report.probes=state.probes;report.players=state.players;report.help=state.help;
   report.controls=events.filter(s=>s.includes('[ui-test-control]')&&s.includes('scene=46 '));
   report.badCoordinates=report.probes.filter(p=>!Number.isFinite(Number(p.y)) || Number(p.y)<-500 || Number(p.y)>1000 || Number(p.height)<=0);
   report.panelVisible=report.panels.some(p=>p.key==='world-options-main'&&p.visibility==='visible');
   await page.keyboard.press('ArrowDown');await sleep(900);
   report.afterArrow=await page.evaluate(()=>[...document.querySelectorAll('.lce-webiggy-menu-help-panel')].filter(e=>getComputedStyle(e).visibility==='visible').map(e=>e.dataset.lceText));
   report.passed=report.panelVisible && report.badCoordinates.length===0 && report.players.some(p=>p.includes('LaunchMoreOptionsMenu')) && report.errors.length===0;
   if(!report.passed)process.exitCode=2;
 }catch(err){report.errors.push('HARNESS: '+String(err.stack||err));process.exitCode=2;}
 finally{
  report.finished=new Date().toISOString();
  fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
  console.log('FIX73_VALIDATION '+JSON.stringify({passed:report.passed,panelVisible:report.panelVisible,players:report.players,panels:report.panels,probes:report.probes,badCoordinates:report.badCoordinates,controls:report.controls?.slice(0,30),errors:report.errors}).slice(0,25000));
  await browser.close().catch(()=>{});
 }
})();
