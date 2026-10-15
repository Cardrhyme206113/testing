'use strict';
// Inspect actual LCE Ruffle controls and native canvas overlays, never mock screens.
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT='fix75-probe';fs.mkdirSync(OUT,{recursive:true});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const report={screens:[],test:[],errors:[]};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const events=[];
 page.on('console',m=>events.push(m.text()));
 page.on('pageerror',e=>report.errors.push(String(e)));
 page.on('dialog',d=>d.dismiss().catch(()=>{}));
 const shot=async name=>{
  await page.screenshot({path:path.join(OUT,name+'.png'),timeout:30000});
  report.screens.push(name);
 };
 const boot=async()=>{
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1&v='+Date.now(),{waitUntil:'domcontentloaded',timeout:120000});
  await wait(33500);
 };
 const open=async scene=>{
  const a=await page.evaluate(k=>Module._lce_web_test_open_ui(k),scene);
  let b=-99;for(let i=0;i<65;i++){await wait(200);b=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(b!==-99)break;}
  await wait(1500);return {a,b};
 };
 const inspect=async()=>page.evaluate(()=>{
  const h=globalThis.LCEWebIggy;
  const names=['CheckboxPVP','CheckboxTrustSystem','CheckboxFireSpreads','CheckboxTNT','CheckboxHostPrivileges','WorldOptions','CheckboxStructures','CheckboxFlatWorld','CheckboxBonusChest'];
  const g=(name,prop)=>{try{return h.getValue(19,name,prop)}catch(e){return 'ERR '+e.message}};
  return {controls:names.map(name=>({name,x:g(name,'x'),y:g(name,'y'),width:g(name,'width'),height:g(name,'height'),sy:g(name,'__lce_stage_y'),sh:g(name,'__lce_stage_height'),visible:g(name,'visible')})),
   panels:[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].map(e=>({key:e.dataset.lcePanelKey,display:getComputedStyle(e).display,visible:getComputedStyle(e).visibility,rect:e.getBoundingClientRect().toJSON()})),players:h.debugState().players.filter(p=>p.visible).map(p=>({id:p.id,filename:p.filename}))};
 });
 try{
  await boot();
  report.test.push({case:'create',open:await open(12)});
  await shot('01-create-normal');
  await page.evaluate(()=>document.querySelectorAll('.lce-webiggy-scale9-panel-overlay').forEach(e=>e.style.display='none'));
  await shot('02-create-without-native-panel');
  await page.mouse.click(642,558);await wait(3800);
  await shot('03-options-normal');
  report.test.push({case:'options-initial',state:await inspect()});
  const set=async ()=>{
    return page.evaluate(()=>{
      const h=globalThis.LCEWebIggy;
      const y={CheckboxOnline:68,CheckboxInviteOnly:102,CheckboxAllowFoF:136,CheckboxPVP:170,CheckboxTrustSystem:204,CheckboxFireSpreads:238,CheckboxTNT:272,CheckboxHostPrivileges:306,CheckboxResetNether:-50,WorldOptions:336,CheckboxStructures:369,CheckboxFlatWorld:403,CheckboxBonusChest:437,Description:496};
      const r=[];
      for(const [name,v] of Object.entries(y)){
        for(const prop of ['y','_y']){
          try {r.push({name,prop,ok:h.setValue(19,name,prop,v)});}catch(e){r.push({name,prop,error:String(e)});}
        }
      }
      return r;
    });
  };
  report.test.push({case:'set-once',result:await set()});
  await wait(700);await shot('04-options-after-set');
  report.test.push({case:'after-set',state:await inspect()});
  await wait(2500);await shot('05-options-set-later');
  report.test.push({case:'later',state:await inspect()});
  // Use native API every animation frame for 2s. This is a diagnostic, not a proposed final method.
  await page.evaluate(()=>{
   const h=globalThis.LCEWebIggy;const ys={CheckboxPVP:170,CheckboxTrustSystem:204,CheckboxFireSpreads:238,CheckboxTNT:272,CheckboxHostPrivileges:306,CheckboxStructures:369,CheckboxFlatWorld:403,CheckboxBonusChest:437};
   let n=0;globalThis.__fix75interval=setInterval(()=>{for(const [k,y] of Object.entries(ys)){h.setValue(19,k,'y',y)}if(++n>150)clearInterval(globalThis.__fix75interval)},16);
  });
  await wait(850);await shot('06-options-continuous');
  await wait(2000);await shot('07-options-after-continuous');
  report.test.push({case:'continuous',state:await inspect()});
  await page.evaluate(()=>document.querySelectorAll('.lce-webiggy-scale9-panel-overlay').forEach(e=>e.style.display='none'));
  await shot('08-options-without-native-panel');
  // Compare settings damaged chrome with original SWF, overlay enabled vs disabled.
  await boot();report.test.push({case:'graphics',open:await open(6)});
  await shot('09-graphics-original');
  report.test.push({case:'graphics-panels',panels:await page.evaluate(()=>[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].map(e=>({key:e.dataset.lcePanelKey,visible:getComputedStyle(e).visibility})) )});
  await page.evaluate(()=>document.querySelectorAll('.lce-webiggy-scale9-panel-overlay,.lce-webiggy-menu-help-panel').forEach(e=>e.style.display='none'));
  await shot('10-graphics-hide-overlays');
  await boot();report.test.push({case:'how-to',open:await open(8)});
  await shot('11-howto-original');
  await page.evaluate(()=>document.querySelectorAll('.lce-webiggy-scale9-panel-overlay,.lce-webiggy-menu-help-panel').forEach(e=>e.style.display='none'));
  await shot('12-howto-hide-overlays');
 }catch(e){report.errors.push('HARNESS: '+String(e.stack||e));}
 finally{report.events=events.filter(s=>/error|warning|shape|geometry|clip|stage/i.test(s)).slice(-40);
  fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
  console.log('FIX75_RESULTS_START');console.log(JSON.stringify(report,null,2).slice(0,65000));console.log('FIX75_RESULTS_END');
  await browser.close().catch(()=>{});
 }
})();
