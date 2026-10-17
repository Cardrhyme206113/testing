'use strict';
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path');
const dir='fix109-evidence';fs.mkdirSync(dir,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const b=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await b.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const result={binary:'Fix96 native wasm + Fix109 native Flash SWFs',scenes:[],errors:[],fatal:[]};
 page.on('pageerror',e=>result.errors.push(String(e.stack||e)));
 page.on('crash',()=>result.fatal.push('chromium crashed'));
 const get=()=>page.evaluate(()=>({
   scenes:(globalThis.LCEWebIggy?.debugState?.()?.players||[]).filter(x=>x.visible).map(x=>x.filename),
   last:typeof Module?._lce_web_test_last_ui_result==='function'?Module._lce_web_test_last_ui_result():null,
   api:typeof Module?._lce_web_test_open_ui==='function',
   customLogoElements:document.querySelectorAll('.lce-webiggy-logo-replacement').length,
   customLayoutElements:document.querySelectorAll('[data-lce-panel-key="controls-layout"]').length,
   isolated:crossOriginIsolated
 }));
 try {
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
  let main=null;
  for(let i=0;i<75;i++){await sleep(1000);main=await get();if(main.scenes.includes('MainMenu720.swf')&&main.api)break;}
  if(!main?.scenes?.includes('MainMenu720.swf'))throw Error('MainMenu did not start');
  await sleep(1500);
  await page.screenshot({path:path.join(dir,'01-logo-main.png')});
  result.scenes.push({name:'main',state:await get()});
  for(const [id,name,expected] of [[9,'controls','Controls720.swf'],[12,'createworld','CreateWorldMenu720.swf']]) {
    const ret=await page.evaluate(id=>Module._lce_web_test_open_ui(id),id);
    let s;
    for(let i=0;i<70;i++){await sleep(300);s=await get();if(s.scenes.includes(expected)&&s.last===1)break;}
    await sleep(700);s=await get();
    result.scenes.push({name,queue:ret,state:s});
    await page.screenshot({path:path.join(dir,'0'+(name==='controls'?2:3)+'-'+name+'.png')});
    if(!s.scenes.includes(expected)||s.last!==1)result.fatal.push(name+' did not load: '+JSON.stringify(s));
  }
  const state=await get();
  if(state.customLogoElements!==0||state.customLayoutElements!==0)
    result.fatal.push('Unexpected external DOM artwork detected');
 }catch(e){result.fatal.push(String(e.stack||e))}
 finally {
  fs.writeFileSync(path.join(dir,'report.json'),JSON.stringify(result,null,2));
  console.log('FIX109_NATIVE_UI '+JSON.stringify({scenes:result.scenes.map(x=>({name:x.name,scene:x.state?.scenes,result:x.state?.last})),errors:result.errors,fatal:result.fatal}));
  await b.close().catch(()=>{});
  if(result.errors.length||result.fatal.length)process.exitCode=1;
 }
})();