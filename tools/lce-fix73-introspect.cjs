'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const out=process.env.UI_TEST_OUT||'fix73-introspect';fs.mkdirSync(out,{recursive:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
let observations=[],consoleEvents=[],exceptions=[];
(async()=>{
const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
page.on('console',m=>{if(consoleEvents.length<6000)consoleEvents.push(m.text());});
page.on('pageerror',e=>exceptions.push(String(e)));
page.on('dialog',d=>d.dismiss().catch(()=>{}));
try{
 await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
 await delay(32000);
 await page.evaluate(()=>{
  const methods=['getValue','setValue','beginCall','pushArg','finishCall','setNativeScale9Panel','setNativeMenuHelpPanel','setNativeContainerPanel','customRegion','dispatchEvent','pathExists','metadata','setVisible','createPlayer'];
  const host=globalThis.LCEWebIggy;window.__fix73Events=[];
  for(const name of methods){
    const orig=host?.[name];
    if(typeof orig!=='function')continue;
    host[name]=function(...args){
      const ret=orig.apply(this,args);
      if(window.__fix73Events.length<10000 && ['getValue','setValue','setNativeScale9Panel','setNativeMenuHelpPanel','beginCall','finishCall','setNativeContainerPanel'].includes(name)){
        window.__fix73Events.push({method:name,args:args.map(x=>String(x).slice(0,150)),ret:ret===undefined?null:String(ret).slice(0,150)});
      }
      return ret;
    };
  }
 });
 let req=await page.evaluate(()=>Module._lce_web_test_open_ui(12));for(let i=0;i<60;i++){await delay(200);if(await page.evaluate(()=>Module._lce_web_test_last_ui_result())!==-99)break}
 await delay(1600);await page.mouse.click(642,558);
 await delay(7500);await page.screenshot({path:path.join(out,'more-options.png'),timeout:30000});
 const result=await page.evaluate(()=>{
  const es=window.__fix73Events||[],p=globalThis.LCEWebIggy?.debugState?.().players||[];
  let methods={};for(const e of es)methods[e.method]=(methods[e.method]||0)+1;
  const forOptions=es.filter(e=>e.args[0]==='19');
  const optionsCalls=forOptions.filter(e=>e.method==='getValue');
  const unique=[...new Map(optionsCalls.map(e=>[JSON.stringify(e.args),e])).values()];
  const otherOptions=forOptions.filter(e=>e.method!=='getValue').slice(-150);
  const probes=[];
  for(const pth of ['MainPanel','MainPanel\\x1fCheckboxPVP','WorldOptions','CheckboxPVP','CheckboxTrustSystem','CheckboxFireSpreads','CheckboxTNT','CheckboxFlatWorld','CheckboxHostPrivileges']){
    for(const prop of ['visible','__lce_stage_x','__lce_stage_y','__lce_stage_width','__lce_stage_height','_x','_y','_width','_height']){
      try{probes.push({path:pth,prop,value:String(globalThis.LCEWebIggy.getValue(19,pth,prop))});}catch(err){probes.push({path:pth,prop,error:String(err).slice(0,90)});}
    }
  }
  return {methods,items:es.slice(-100),optionsGetValue:unique.slice(0,160),optionsOther:otherOptions,probes,players:p.filter(x=>x.visible).map(x=>({filename:x.filename,id:x.id})),panels:[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].map(x=>({key:x.dataset.lcePanelKey,visibility:getComputedStyle(x).visibility}))}
 });
 observations=result;
} catch(e){exceptions.push('HARNESS: '+String(e.stack||e))}
finally{
 let controls=consoleEvents.filter(x=>x.includes('[ui-test-control]')&&x.includes('scene=46 '));
 fs.writeFileSync(path.join(out,'report.json'),JSON.stringify({observations,controls,exceptions,events:consoleEvents.filter(x=>/warn|failed|exception|error|MoreOptions|world-options|scene=46/i.test(x)).slice(-100)},null,2));
 console.log('FIX73_REPORT '+JSON.stringify({methods:observations.methods,players:observations.players,panels:observations.panels,controls,optionsGetValue:observations.optionsGetValue,optionsOther:observations.optionsOther,probes:observations.probes,exceptions}).slice(0,25000));
 await browser.close().catch(()=>{});
}
})();
