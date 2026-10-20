'use strict';
const {chromium}=require('playwright'),fs=require('fs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const b=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await b.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const report={scenes:[],errors:[]};page.on('pageerror',e=>{if(!String(e.message).includes('Incorrect locale information provided'))report.errors.push(e.message)});
 const state=()=>page.evaluate(()=>({visible:(globalThis.LCEWebIggy?.debugState?.()?.players||[]).filter(x=>x.visible).map(x=>x.filename),last:globalThis.Module?._lce_web_test_last_ui_result?.()}));
 async function screenshot(label){
   const buffer=await page.screenshot({timeout:25000});
   const str=buffer.toString('base64');const filename=label+'.png';
   for(let off=0;off<str.length;off+=20000)console.log('SLIDER_IMAGE_PART',filename,off/20000,str.slice(off,off+20000));
 }
 try {
  await page.goto('http://127.0.0.1:8765/?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
  await page.waitForFunction(()=>globalThis.LCEWebIggy?.debugState?.()?.players?.some(x=>x.visible&&x.filename==='MainMenu720.swf'),undefined,{timeout:105000});
  for(const [id,name] of [[4,'audio'],[6,'graphics']]){
   const r=await page.evaluate(id=>Module._lce_web_test_open_ui(id),id);
   for(let n=0;n<90;n++){await sleep(200);const s=await state();if(s.last!==-99)break}
   await sleep(800);
   const s=await state();report.scenes.push({id,name,request:r,...s});console.log('SLIDER_SCENE',JSON.stringify(report.scenes.at(-1)));
   if(r!==1||s.last!==1)throw Error('native menu '+name+' did not complete');
   await screenshot(name+'-before');
  }
 }catch(e){report.errors.push(String(e.stack||e))}
 finally{console.log('FIX117_SLIDERS_REPORT',JSON.stringify(report));await b.close();if(report.errors.length)process.exitCode=1}
})();
