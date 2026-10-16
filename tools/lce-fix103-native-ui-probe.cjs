'use strict';
const { chromium } = require('playwright');
const fs = require('fs');const path=require('path');
const OUT='fix103-evidence';fs.mkdirSync(OUT,{recursive:true});
const pause=t=>new Promise(r=>setTimeout(r,t));
(async()=>{
const b=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
const page=await b.newPage({viewport:{width:1280,height:800},locale:'en-US'});
const report={provenance:'Fix96 binary, not Fix100 native recompilation',loaded:false,scenes:[],errors:[],consoleErrors:[],samples:[],badLocales:[],fatal:[]};
page.on('pageerror',e=>report.errors.push({message:e.message,stack:e.stack}));
page.on('crash',()=>report.fatal.push('chromium page crashed'));
page.on('console',m=>{if(m.type()==='error')report.consoleErrors.push(m.text().slice(0,1200))});
page.on('dialog',d=>d.dismiss().catch(()=>{}));
await page.addInitScript(()=>{
 globalThis.__lceBadLocales=[];
 for(const key of ['DateTimeFormat','NumberFormat','Collator','PluralRules','RelativeTimeFormat','ListFormat','DisplayNames']){
  const Original=Intl[key];if(typeof Original!=='function')continue;
  const Hook=function(...args){
   try{return Reflect.construct(Original,args,new.target||Original)}
   catch(error){globalThis.__lceBadLocales.push({type:key,locale:String(args[0]),message:String(error),stack:String(error.stack).slice(0,1400)});throw error;}
  };
  Object.setPrototypeOf(Hook,Original);
  Hook.prototype=Original.prototype;
  Intl[key]=Hook;
 }
});
const state=()=>page.evaluate(()=>{
 const h=globalThis.LCEWebIggy?.debugState?.();
 return {players:(h?.players||[]).filter(x=>x.visible).map(x=>x.filename),
   requested:typeof Module?._lce_web_test_last_ui_result==='function'?Module._lce_web_test_last_ui_result():null,
   ready:typeof Module?._lce_web_test_open_ui==='function',
   heap:performance.memory?.usedJSHeapSize??null,isolated:crossOriginIsolated,
   status:document.getElementById('status')?.textContent?.slice(0,200)};
});
try{
 await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
 for(let t=0;t<65;t++){await pause(1000);let s=await state();
  if(t%10===0)report.samples.push({at:t,...s});
  if(s.players.includes('MainMenu720.swf')&&s.ready){report.loaded=true;break;}
 }
 if(!report.loaded)report.fatal.push('MainMenu native API not ready within 65s');
 else {
  await page.screenshot({path:path.join(OUT,'00-main-menu.png')});
  const kinds=[0,1,2,3,4,5,6,7,8,9,10,11,12];
  const names=['MainMenu','HelpAndOptions','Settings','SettingsOptions','SettingsAudio','SettingsControl','SettingsGraphics','SettingsUI','HowToPlay','Controls','SkinSelect','Credits','CreateWorld'];
  for(let i=0;i<kinds.length;i++){
   const id=kinds[i],name=names[i];let r;
   try{r=await page.evaluate(id=>Module._lce_web_test_open_ui(id),id)}
   catch(e){report.fatal.push('native test hook '+name+': '+e);continue}
   let s=null;for(let j=0;j<48;j++){await pause(250);s=await state();if(s.requested!==-99)break;}
   await pause(450);s=await state();
   report.scenes.push({id,name,enqueue:r,...s});
   try{await page.screenshot({path:path.join(OUT,String(id+1).padStart(2,'0')+'-'+name+'.png'),timeout:20000})}
   catch(e){report.fatal.push('screenshot '+name+': '+e)}
   // Input test on a native Options scene: change focus with arrows and
   // click actual canvas, with no HTML-generated controls or fake keyboard bridge.
   if([2,3,4,5,6,7,12].includes(id)&&s.requested===1){
    const before=await state();
    await page.keyboard.press('ArrowDown');await page.keyboard.press('ArrowRight');
    await page.mouse.move(610,460);await page.mouse.click(610,460);
    await pause(700);
    const after=await state();
    report.scenes.at(-1).inputTest={before,after};
   }
  }
 }
 report.badLocales=await page.evaluate(()=>globalThis.__lceBadLocales||[]);
}catch(e){report.fatal.push('test runner: '+String(e.stack||e))}
finally{
 fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
 console.log('FIX103_AUDIT '+JSON.stringify({loaded:report.loaded,scenes:report.scenes.map(x=>({name:x.name,result:x.requested,players:x.players})),errors:report.errors.slice(0,3),locale:report.badLocales.slice(0,3),fatal:report.fatal}));
 await b.close().catch(()=>{});
 if(report.fatal.length||!report.loaded||report.scenes.some(x=>x.requested!==1))process.exitCode=1;
}
})();