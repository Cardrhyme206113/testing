'use strict';
// Exercise the REAL DebugMenu720.swf through browser key events. Other menus
// use the existing native Common/UI test hook. Do not insert fake UI elements.
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const dir='fix112-debug-stress';fs.mkdirSync(dir,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const cdp=await page.context().newCDPSession(page);await cdp.send('Performance.enable');
 const out={build:'Fix110 original-alpha WASM runtime / native Flash; no C++ Fix111 compiled',baseline:[],cycles:[],errors:[],fatal:[],events:[],debugObserved:0,debugOpened:0,debugClosed:0,navAttempts:0,navSuccess:0,unexpectedScenePersistence:0};
 page.on('pageerror',e=>out.errors.push({type:'pageerror',message:String(e.stack||e)}));
 page.on('crash',()=>out.fatal.push('renderer process crashed'));
 page.on('console',m=>{if(m.type()==='error'&&out.errors.length<150)out.errors.push({type:'console',message:m.text().slice(0,700)})});
 await page.addInitScript(()=>{
   const orig=WebAssembly.instantiate;
   WebAssembly.instantiate=async function(...a){const res=await orig.apply(this,a);const mem=(res?.instance||res)?.exports?.memory;if(mem instanceof WebAssembly.Memory)globalThis.__lceWasmMemory=mem;return res};
   if(WebAssembly.instantiateStreaming){const orig2=WebAssembly.instantiateStreaming;WebAssembly.instantiateStreaming=async function(...a){const res=await orig2.apply(this,a);const mem=res?.instance?.exports?.memory;if(mem instanceof WebAssembly.Memory)globalThis.__lceWasmMemory=mem;return res}};
 });
 async function snapshot(label){
   const p=await page.evaluate(()=>{
     const a=globalThis.LCEWebIggy?.debugState?.()?.players||[];
     const v=a.filter(x=>x.visible);
     const t=globalThis.__fix112?.times||[];
     const sorted=t.slice().sort((a,b)=>a-b);const quant=q=>sorted.length?sorted[Math.min(sorted.length-1,Math.floor(sorted.length*q))]:null;
     const cp=globalThis.__fix112||{};
     return {players:a.length,visible:v.length,hidden:a.length-v.length,sceneNames:v.map(x=>x.filename),allSceneNames:a.map(x=>x.filename),ruffleElements:document.querySelectorAll('ruffle-player').length,canvasElements:document.querySelectorAll('canvas').length,wasmBytes:globalThis.__lceWasmMemory?.buffer?.byteLength??null,rafFrames:cp.frames||0,frameMsP50:quant(.5),frameMsP95:quant(.95),frameMsP99:quant(.99),lastUi:globalThis.Module?._lce_web_test_last_ui_result?.()??null,heapReported:performance.memory?.usedJSHeapSize??null};
   });
   const m=await cdp.send('Performance.getMetrics');const perf=Object.fromEntries(m.metrics.map(x=>[x.name,x.value]));
   return {label,timeMs:Date.now(),...p,cdpHeapBytes:perf.JSHeapUsedSize,cdpTaskDuration:perf.TaskDuration,cdpNodes:perf.Nodes,cdpDocuments:perf.Documents};
 }
 async function record(name,collection=out.events){const snap=await snapshot(name);collection.push(snap);console.log('FIX112_PROGRESS',JSON.stringify({label:name,players:snap.players,hidden:snap.hidden,debug:snap.sceneNames.includes('DebugMenu720.swf'),wasm:snap.wasmBytes,js:snap.cdpHeapBytes,frameP95:snap.frameMsP95}));return snap}
 async function shot(name){try{await page.screenshot({path:path.join(dir,name+'.png'),timeout:25000})}catch(e){out.errors.push({type:'screenshot',message:name+': '+e})}}
 async function isDebug(){const x=await page.evaluate(()=>globalThis.LCEWebIggy?.debugState?.()?.players?.some(x=>x.visible&&x.filename==='DebugMenu720.swf')||false);return x}
 async function press(key,hold=115){await page.keyboard.down(key);await sleep(hold);await page.keyboard.up(key);await sleep(750)}
 async function tryCloseDebug(){
   if(!(await isDebug()))return true;
   for(const key of ['Backquote','Escape','Tab']){
     await press(key);
     if(!(await isDebug())){out.debugClosed++;return true}
   }
   return false;
 }
 // Existing native test hook runs normal app/LocalPlayer constructors and closes
 // earlier scenes after the native close countdown; no synthetic HTML UI.
 async function openNative(id,name){
   out.navAttempts++;
   let q=await page.evaluate(id=>Module._lce_web_test_open_ui(id),id);
   if(q!==1){out.events.push({label:'native request rejected '+name,id,result:q});return false;}
   let s;
   for(let i=0;i<95;i++){
     await sleep(200);
     s=await snapshot('nav '+name);
     if(s.lastUi!==-99)break;
   }
   if(s?.lastUi===1)out.navSuccess++;
   else out.events.push({label:'native constructor failed '+name,id,last:s?.lastUi});
   return s?.lastUi===1;
 }
 try{
   await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
   let started=false;
   for(let i=0;i<90;i++){await sleep(1000);const s=await snapshot('boot');if(s.sceneNames.includes('MainMenu720.swf')&&typeof s.lastUi==='number'){started=true;break}}
   if(!started)throw Error('MainMenu not ready');
   await page.evaluate(()=>{
     const p={times:[],frames:0};globalThis.__fix112=p;
     let old=0;function raf(now){if(old){p.times.push(now-old);if(p.times.length>1500)p.times.shift()}old=now;p.frames++;requestAnimationFrame(raf)}requestAnimationFrame(raf);
   });
   const original=await record('original main');
   if(!(await openNative(12,'CreateWorldMenu')))throw Error('Create World menu unavailable');
   await page.mouse.click(640,605);
   let world=false;
   for(let i=0;i<165;i++){
     await sleep(1000);
     const s=await snapshot('loading '+i);
     if(i%30===0)out.events.push(s);
     if(i>17&&!s.sceneNames.some(x=>/CreateWorldMenu|FullscreenProgress|Loading|Saving/.test(x))){
       await sleep(3000);
       const w=await snapshot('post-load');
       if(!w.sceneNames.some(x=>/CreateWorldMenu|FullscreenProgress|Loading|Saving/.test(x))){world=true;break;}
     }
   }
   if(!world)throw Error('World did not reach post-loading scene');
   await record('world loaded');await shot('01-world-before-stress');
   for(let i=0;i<3;i++){await sleep(5000);await record('baseline-'+i,out.baseline)}
   // Probe actual existing browser key mappings. These are native game inputs,
   // not calls to synthetic debug scenes. Require DebugMenu720.swf to be visible.
   const triggers=['ShiftLeft','ControlLeft','Tab','F5','F3','ShiftRight'];
   let activeTrigger=null;
   for(const key of triggers){
     await press(key);
     const opened=await isDebug();
     out.events.push({label:'debug-key-probe',key,opened});
     if(opened){activeTrigger=key;out.debugObserved++;out.debugOpened++;await shot('02-debug-open-'+key);break;}
   }
   if(!activeTrigger){
     out.fatal.push('DebugMenu720.swf did not appear for any tested real key; cannot claim debug-menu performance was exercised');
   } else {
     const closed=await tryCloseDebug();
     out.events.push({label:'initial-debug-close',closed});
     await shot('03-debug-after-close');
     // Deliberate repeated debug open -> random native UI -> close -> debug open.
     // Deterministic pseudo-random sequence makes failures reproducible.
     const nav=[
       {id:20,name:'inventory'},{id:30,name:'pause'},{id:21,name:'crafting2x2'},
       {id:29,name:'creative'},{id:3,name:'settings-options'},{id:9,name:'controls'}
     ];
     let seed=0x1ce112;const next=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed};
     for(let i=0;i<20;i++){
       await press(activeTrigger);
       let debugVisible=await isDebug();
       if(debugVisible){out.debugOpened++;out.debugObserved++;}
       let before=await record('cycle-'+i+'-debug-open',out.cycles);
       if(i===0||i===9||i===19)await shot('04-cycle-'+i+'-debug-open');
       const ui=nav[next()%nav.length];
       const navOk=await openNative(ui.id,ui.name);
       await record('cycle-'+i+'-after-'+ui.name,out.cycles);
       await press('ArrowDown');await press('ArrowRight');
       await press('Backquote');
       await sleep(650);
       let closed=await tryCloseDebug();
       if(debugVisible && closed)out.debugClosed++;
       const after=await record('cycle-'+i+'-closed',out.cycles);
       if(after.sceneNames.includes('DebugMenu720.swf'))out.unexpectedScenePersistence++;
       out.events.push({label:'cycle-result',i,key:activeTrigger,ui:ui.name,debugVisible,navOk,closed,playersBefore:before.players,playersAfter:after.players,wasmBefore:before.wasmBytes,wasmAfter:after.wasmBytes});
       if(i===0||i===9||i===19)await shot('05-cycle-'+i+'-after-close');
     }
   }
   // Cool-down after stress to catch leaks that only become visible after scene destruction.
   for(let i=0;i<9;i++){await sleep(5000);await record('cooldown-'+i,out.baseline)}
 }catch(e){out.fatal.push(String(e.stack||e))}
 finally{
   fs.writeFileSync(path.join(dir,'report.json'),JSON.stringify(out,null,2));
   console.log('FIX112_RESULT',JSON.stringify({debugObserved:out.debugObserved,debugOpened:out.debugOpened,debugClosed:out.debugClosed,unexpectedScenePersistence:out.unexpectedScenePersistence,navAttempts:out.navAttempts,navSuccess:out.navSuccess,cycles:out.cycles.length,errors:out.errors.length,fatal:out.fatal,begin:out.baseline[0],end:out.baseline.at(-1)}));
   await browser.close().catch(()=>{});
   if(out.fatal.length||!out.debugObserved||out.navSuccess===0)process.exitCode=1;
 }
})();