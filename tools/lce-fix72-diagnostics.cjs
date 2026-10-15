'use strict';
// Fix72 runtime diagnosis: read-only, never replaces the original LCE menu.
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT=process.env.UI_TEST_OUT||'fix72-evidence';fs.mkdirSync(OUT,{recursive:true});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const report={moreOptions:{},inventory:{},errors:[],events:[]};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const events=[];page.on('console',m=>{events.push({ts:Date.now(),type:m.type(),message:m.text()});});
 page.on('pageerror',e=>report.errors.push(String(e)));
 page.on('crash',()=>report.errors.push('Chrome page crashed'));
 page.on('dialog',d=>d.dismiss().catch(()=>{}));
 const seen=()=>[...new Map(events.filter(e=>e.message.includes('[ui-test-control]')).map(e=>[e.message,e.message])).values()];
 const state=()=>page.evaluate(()=>{
   const r=globalThis.LCEWebIggy?.debugState?.();
   const c=document.getElementById('canvas');
   const nodes=s=>[...document.querySelectorAll(s)].filter(e=>getComputedStyle(e).visibility==='visible').map(e=>({tag:e.tagName,text:e.dataset.lceText||'',key:e.dataset.lcePanelKey||'',rect:e.getBoundingClientRect().toJSON()}));
   return {root:r?.root,players:r?.players?.filter(x=>x.visible).map(x=>x.filename),canvas:c?.getBoundingClientRect().toJSON(),help:nodes('.lce-webiggy-menu-help-panel'),scale9:nodes('.lce-webiggy-scale9-panel-overlay'),active:document.activeElement?.tagName,flags:{isolated:crossOriginIsolated,keys:Object.keys(globalThis.LCEWebIggy||{}).slice(0,30)}};
 });
 const open=async kind=>{
  const req=await page.evaluate(k=>Module._lce_web_test_open_ui(k),kind);
  if(req!==1)return {request:req,result:req};
  let result=-99;
  for(let i=0;i<70;i++){await wait(250);result=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(result!==-99)break;}
  return {request:req,result};
 };
 const snap=async name=>{await page.screenshot({path:path.join(OUT,name+'.png'),timeout:30000});};
 try{
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
  await wait(33000);
  report.moreOptions.create=await open(12);await wait(1200);
  report.moreOptions.before=await state(); await snap('01-createworld');
  await page.mouse.click(642,558);
  for(let i=0;i<90;i++){if((await state()).players?.some(p=>p.includes('LaunchMoreOptionsMenu')))break;await wait(250);}
  await wait(1800);
  report.moreOptions.opened=await state();await snap('02-moreoptions');
  report.moreOptions.controls=seen().filter(x=>x.includes('scene=46 '));
  report.moreOptions.invalidControls=report.moreOptions.controls.filter(line=>/y=-107374182|rect=\\d+,-107374182/.test(line));
  for(const key of ['ArrowDown','Tab','Enter','Escape']){
    const since=events.length;
    await page.keyboard.press(key);await wait(950);
    report.moreOptions[key]={state:await state(),nativeMessages:events.slice(since).filter(x=>/ui-test-control|error|swf|warn/i.test(x.message)).slice(-30)};
  }
  await snap('03-options-after-keys');
  // A fresh load separates the menu-state defect from the in-world inventory defect.
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1&world=1',{waitUntil:'domcontentloaded',timeout:120000});
  await wait(33000);report.inventory.createScene=await open(12);await wait(1200);
  await page.mouse.click(642,608);await wait(15000);
  report.inventory.after15=await state();
  let probe=-99;
  for(let i=0;i<55;i++){
    const o=await open(20);probe=o.result;
    if(probe===0||probe===1)break;
    await wait(2500);
  }
  report.inventory.nativeProbe=probe;report.inventory.afterProbe=await state();
  await snap('04-inventory-native-probe');
  const beforeKeys=events.length;
  await page.keyboard.press('e');await wait(3600);
  report.inventory.keyboardE={state:await state(),logs:events.slice(beforeKeys).filter(x=>/inventory|ui-test|error|warn|open/i.test(x.message)).slice(-35)};
  await page.keyboard.press('Escape');await wait(900);
  report.inventory.afterEscape=await state();
  await snap('05-inventory-keyboard');
  await page.keyboard.press('Escape');await wait(500);
  report.inventory.probeAgain=await open(20);await wait(1300);
  report.inventory.afterRetry=await state();await snap('06-inventory-retry');
  report.events=events.filter(x=>/error|warn|Exception|InventoryMenu|GetPosition|nan|NaN/i.test(x.message)).slice(-90);
 }catch(e){report.errors.push('HARNESS: '+String(e.stack||e));}
 finally{
  report.completed=new Date().toISOString();
  fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
  const rows={moreOptions:{create:report.moreOptions.create,controls:report.moreOptions.controls,initial:report.moreOptions.opened,afterKeys:Object.fromEntries(['ArrowDown','Tab','Enter','Escape'].map(k=>[k,report.moreOptions[k]?.state]))},inventory:report.inventory,errors:report.errors};
  console.log('FIX72_SUMMARY_START');console.log(JSON.stringify(rows,null,2).slice(0,50000));console.log('FIX72_SUMMARY_END');
  await browser.close().catch(()=>{});
 }
})();
