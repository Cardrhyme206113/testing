'use strict';
const {chromium}=require('playwright');
const fs=require('fs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const dir='fix117-evidence';fs.mkdirSync(dir,{recursive:true});
const output={static:{},startup:{},samples:[],nav:[],errors:[],failures:[]};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const pageErrors=[];
 page.on('pageerror',e=>{if(!String(e).includes('Incorrect locale information provided'))pageErrors.push(String(e).slice(0,700))});
 page.on('crash',()=>output.failures.push('browser crashed'));
 const code=fs.readFileSync('site/lce_web_demo.js','utf8');
 for(const needle of ['FS.syncfs','FS.mount','IDBFS','MEMFS','FS.readdir','FS.readFile','FS.writeFile','FS.mkdir','FS.unlink','_lce_web_test_open_ui','saveWorld','saveFile','SaveData','Download','requestPointerLock','webIggyTick','function tickPlayer','waitForPlayerPreload']){
   const i=code.indexOf(needle);output.static[needle]={occurrences:code.split(needle).length-1,first:i,excerpt:i<0?'':code.slice(Math.max(0,i-150),i+260)};
 }
 const read=()=>page.evaluate(()=>{
   const host=globalThis.LCEWebIggy||{},obj=host?.debugState?.()||{};
   const FSAPI=globalThis.FS||globalThis.Module?.FS;
   const choose=['/','/home','/home/web_user','/tmp','/saves','/save','/minecraft','/persistent','/IDBFS'];
   const fsPaths={};
   if(FSAPI)for(const p of choose){try{fsPaths[p]=FSAPI.readdir(p)}catch(e){fsPaths[p]=String(e).slice(0,85)}}
   return {
    scenes:(obj.players||[]).filter(x=>x.visible).map(x=>x.filename),
    players:obj.players?.length,
    menuVisible:obj.menuVisible,
    keys: Object.keys(host).slice(0,65),
    ModuleKeys:Object.keys(globalThis.Module||{}).filter(x=>/save|storage|file|FS|sync|world|test/i.test(x)).slice(0,100),
    FSavailable:!!FSAPI, FSOps:FSAPI?Object.keys(FSAPI).filter(k=>/sync|mount|read|write|mkdir|readdir/.test(k)).slice(0,65):[],
    fsPaths, heap:performance.memory?.usedJSHeapSize, COI:crossOriginIsolated,
    lastUi:globalThis.Module?._lce_web_test_last_ui_result?.()
   };
 });
 try{
   await page.goto('http://127.0.0.1:8765/?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
   await page.waitForFunction(()=>crossOriginIsolated&&navigator.serviceWorker?.controller,undefined,{timeout:25000});
   for(let i=0;i<105;i++){
     await sleep(1000);
     const state=await read();
     if(state.scenes.includes('MainMenu720.swf')&&typeof state.lastUi==='number')break;
   }
   output.startup=await read();
   if(!output.startup.scenes.includes('MainMenu720.swf')) throw Error('Native main menu missing');
   console.log('FIX117_BOOT',JSON.stringify(output.startup));
   async function open(n,name){
     const req=await page.evaluate(n=>Module._lce_web_test_open_ui(n),n);let val;
     for(let i=0;i<95;i++){
       await sleep(180);val=await read();if(val.lastUi!==-99)break;
     }
     const out={name,request:req,...val};output.nav.push(out);
     console.log('FIX117_NAV',JSON.stringify({name,request:req,scenes:val?.scenes,players:val?.players,last:val?.lastUi}));
     return out;
   }
   const opt=await open(3,'settings options');
   await sleep(1500);
   await page.screenshot({path:dir+'/01-options-before.png',timeout:20000});
   // Measure mouse dragging vs directional input in real native Settings.
   await page.mouse.move(550,368);
   await page.mouse.down();
   await page.mouse.move(810,368,{steps:20});
   await page.mouse.up();
   await sleep(900);
   await page.screenshot({path:dir+'/02-options-after-mousedrag.png',timeout:20000});
   await page.keyboard.press('ArrowRight');
   await sleep(900);
   await page.screenshot({path:dir+'/03-options-after-arrow-right.png',timeout:20000});
   const control=await open(9,'controls');
   await page.screenshot({path:dir+'/04-controls.png',timeout:20000});
   output.samples.push({phase:'after-ui',...(await read())});
   await open(12,'create world');
   await page.screenshot({path:dir+'/05-create-world.png',timeout:20000});
   await page.mouse.click(640,605);
   for(let i=0;i<95;i++){
     await sleep(1000);
     if(i%10===0){
       const x=await read();
       output.samples.push({phase:'world-loading',seconds:i,...x});
       console.log('FIX117_WORLD',JSON.stringify({second:i,scenes:x.scenes,fsPaths:x.fsPaths,FS:x.FSavailable}));
     }
   }
   output.samples.push({phase:'world-after-load',...(await read())});
   await page.screenshot({path:dir+'/06-world-post-load.png',timeout:20000});
   await page.keyboard.press('Escape');
   await sleep(1800);
   const afterEscape=await read();
   output.samples.push({phase:'after-Escape-no-menu',...afterEscape});
   console.log('FIX117_ESCAPE',JSON.stringify({before:output.samples.at(-2).scenes,after:afterEscape.scenes,menuVisible:afterEscape.menuVisible}));
   await page.screenshot({path:dir+'/07-after-escape.png',timeout:20000});
   if(!afterEscape.scenes.some(x=>/PauseMenu/.test(x))){
     await page.keyboard.press('Backquote');
     await sleep(1800);
     const afterBackquote=await read();
     output.samples.push({phase:'after-Backquote-fallback',...afterBackquote});
     console.log('FIX117_BACKQUOTE_FALLBACK',JSON.stringify({after:afterBackquote.scenes,menuVisible:afterBackquote.menuVisible}));
     await page.screenshot({path:dir+'/08-after-backquote.png',timeout:20000});
   }
 }catch(e){output.failures.push(String(e.stack||e));}
 finally{
   output.errors=pageErrors.slice(0,40);
   fs.writeFileSync(dir+'/report.json',JSON.stringify(output,null,2));
   console.log('FIX117_AUDIT_SUMMARY',JSON.stringify({static:output.static,boot:output.startup,nav:output.nav.map(x=>({name:x.name,request:x.request,scenes:x.scenes,last:x.lastUi})),world:output.samples.slice(-1),failures:output.failures,errors:output.errors.length}));
   await browser.close();if(output.failures.length)process.exitCode=1;
 }
})();
