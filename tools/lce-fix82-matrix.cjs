'use strict';
/*
 * Fix82: independently audited full UI matrix against the real C++ and SWFs.
 * Non-testable states are flagged as MISSING COVERAGE, never counted as passes.
 * Screenshots are unmodified Chromium framebuffer captures.
 */
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT=path.resolve(process.env.UI_TEST_OUT||'fix82-matrix');
const GROUP=process.env.UI_GROUP||'front';
fs.mkdirSync(OUT,{recursive:true});
const wait=t=>new Promise(r=>setTimeout(r,t));
const front=[[0,'MainMenu'],[1,'HelpAndOptions'],[2,'Settings'],[3,'SettingsOptions'],[4,'SettingsAudio'],[5,'SettingsControls'],[6,'SettingsGraphics'],[7,'SettingsUI'],[8,'HowToPlay'],[9,'ControllerLayout'],[10,'SkinSelect'],[11,'Credits'],[12,'CreateWorld'],[13,'WorldOptions']];
const ingame=[[20,'Inventory'],[21,'Crafting2x2'],[22,'Crafting3x3'],[23,'Furnace'],[24,'Chest'],[25,'Enchanting'],[26,'Brewing'],[27,'Dispenser'],[28,'Anvil'],[29,'Creative'],[30,'Pause']];
const report={group:GROUP,created:new Date().toISOString(),cases:[],browserErrors:[],resourceFailures:[],rendererTraces:[],worldLive:false,limitations:[],summary:{}};
let page,context;
const browserArgs=['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US'];
const slim=x=>x?.map(v=>v.filename);
async function main(){
 const browser=await chromium.launch({headless:true,args:browserArgs});
 context=await browser.newContext({viewport:{width:1280,height:800},locale:'en-US'});
 const hook=()=>{
  page.on('console',m=>{const msg=m.text();if(msg.includes('[frame-tree]')&&report.rendererTraces.length<150)report.rendererTraces.push(msg.slice(0,600));});
  page.on('pageerror',e=>report.browserErrors.push(String(e.stack||e).slice(0,2500)));
  page.on('crash',()=>report.browserErrors.push('page crash'));
  page.on('requestfailed',r=>report.resourceFailures.push({url:r.url(),error:r.failure()?.errorText}));
  page.on('dialog',d=>d.dismiss().catch(()=>{}));
 };
 const boot=async()=>{
  if(page)await page.close().catch(()=>{});
  page=await context.newPage();hook();
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
  await wait(Number(process.env.UI_BOOT_MS||33000));
  const result=await page.evaluate(()=>({ready:typeof Module?._lce_web_test_open_ui==='function',isolated:crossOriginIsolated}));
  if(!result.ready || !result.isolated)throw Error('Original C++ UI hooks/WASM isolation not available: '+JSON.stringify(result));
 };
 const open=async k=>{
  const req=await page.evaluate(k=>Module._lce_web_test_open_ui(k),k).catch(()=>-201);
  if(req!==1)return {request:req,result:req};
  let result=-99;
  for(let i=0;i<65;i++){await wait(200);result=await page.evaluate(()=>Module._lce_web_test_last_ui_result()).catch(()=>-202);if(result!==-99)break;}
  await wait(600);
  return {request:req,result};
 };
 const state=async()=>page.evaluate(()=>{
   const h=globalThis.LCEWebIggy;
   const p=h?.debugState()?.players?.filter(x=>x.visible)||[];
   const panels=[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].filter(x=>getComputedStyle(x).visibility==='visible').map(x=>x.dataset.lcePanelKey);
   const standalone=p.filter(x=>/Menu720[.]swf$/i.test(x.filename)).map(x=>{
     let exists=false,hidden=null;
     try{exists=h.pathExists(x.id,'BackgroundPanel');if(exists)hidden=h.getValue(x.id,'BackgroundPanel','visible')===false;}catch(e){}
     return {filename:x.filename,id:x.id,hasBackground:exists,backgroundHidden:hidden};
   });
   return {players:p.map(x=>({filename:x.filename,id:x.id})),panels,standalone,root:h?.debugState()?.root};
 });
 const capture=async name=>{
  const safe=name.replace(/[^A-Za-z0-9-]/g,'-');
  await page.screenshot({path:path.join(OUT,safe+'.png'),timeout:35000});
  return safe+'.png';
 };
 const inspect=async(id,label,opts={})=>{
  const entry={id,label,status:'unknown',errorsBefore:report.browserErrors.length};
  report.cases.push(entry);
  try{
   if(id===13){
     const x=await open(12);
     if(x.result!==1){entry.status='blocked-create-world';entry.open=x;return}
     await wait(1100);
     await page.mouse.click(642,558);
     for(let j=0;j<90;j++){await wait(250);const v=await state();if(v.players.some(p=>p.filename==='LaunchMoreOptionsMenu720.swf'))break;}
     entry.open={result:1};
   }else entry.open=await open(id);
   await wait(1400);
   entry.before=await state();
   entry.screenshot=await capture(String(id).padStart(2,'0')+'-'+label);
   if(entry.open.result!==1){entry.status='not-created';return}
   const expected=id===13?'LaunchMoreOptionsMenu720.swf':null;
   if(expected&&!entry.before.players.some(x=>x.filename===expected)){entry.status='wrong-screen';return}
   entry.frames=entry.before.standalone.map(x=>({...x,pass:!x.hasBackground||(x.backgroundHidden&&entry.before.panels.length>0)}));
   const filesBefore=slim(entry.before.players);
   await page.keyboard.press('ArrowDown');await wait(450);
   entry.navigationScreenshot=await capture(String(id).padStart(2,'0')+'-'+label+'-down');
   entry.afterArrow=await state();
   await page.keyboard.press('ArrowUp');await wait(250);
   entry.navigationResponded=JSON.stringify(entry.before)!==JSON.stringify(entry.afterArrow);
   entry.status=report.browserErrors.length===entry.errorsBefore?'captured':'browser-error';
  }catch(e){entry.status='harness-error';entry.error=String(e.stack||e).slice(0,2600);}
  finally{fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));}
 };
 try{
  if(GROUP==='front'){
   await boot();
   for(const [id,name] of front){
     // Recover from navigation stacks via native test scene open; if failed,
     // retry on a fresh emulator boot rather than mislabel the screenshot.
     await inspect(id,name);
     if(report.cases.at(-1).status==='not-created'){
       await boot();
       report.cases.pop();
       await inspect(id,name);
     }
   }
  }else if(GROUP==='ingame'){
   await boot();
   const cr=await open(12);
   if(cr.result===1){
     await capture('00-world-create-before');
     await page.mouse.click(642,608);
     await wait(18000);
     for(let i=0;i<70;i++){
       const r=await open(20);
       if(r.result===0||r.result===1){
         report.worldLive=true;
         report.firstInventoryProbe=r;
         break;
       }
       await wait(2100);
     }
   }
   await capture('00-world-create-after');
   if(!report.worldLive)report.limitations.push('World did not start: cannot test real in-game UIs.');
   else{
     // Press Escape to close any intro or incomplete inventory UI.
     await page.keyboard.press('Escape');await wait(600);
     for(const [id,name] of ingame){
       await page.keyboard.press('Escape');await wait(450);
       await inspect(id,name);
       if(report.cases.at(-1)?.status==='not-created'){
         // Runtime C++ requires a valid block entity (chest, furnace,
         // brewing stand, etc.). Not created != tested and passed.
         report.cases.at(-1).status='native-precondition-unmet';
       }
     }
     // Verify actual E key input as alternative to native inventory hook.
     await page.keyboard.press('Escape');await wait(400);
     await page.keyboard.press('e');await wait(2600);
     const now=await state();
     report.keyboardInventory={visible:now.players.some(p=>p.filename==='InventoryMenu720.swf'),screen:await capture('31-inventory-keyboard-e'),state:now};
   }
   report.limitations.push('Trading/merchant, writable books, signs, death, sleep, chat, map, achievements, and block-specific dialogues need dedicated C++ test fixtures and are not testable by the existing selectors.');
  }else throw Error('UI_GROUP must be front or ingame');
 }catch(e){report.browserErrors.push('MATRIX: '+String(e.stack||e));}
 finally{
  report.summary={requested:GROUP==='front'?front.length:ingame.length,created:report.cases.filter(x=>x.open?.result===1).length,captured:report.cases.filter(x=>x.status==='captured').length,unavailable:report.cases.filter(x=>x.status!=='captured').map(x=>({id:x.id,label:x.label,status:x.status})),browserErrors:report.browserErrors.length,worldLive:report.worldLive,frameFailures:report.cases.filter(x=>x.frames?.some(f=>!f.pass)).map(x=>x.label)};
  report.complete=report.summary.captured===report.summary.requested && report.summary.browserErrors===0 && report.summary.frameFailures.length===0;
  report.finished=new Date().toISOString();
  fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
  fs.writeFileSync(path.join(OUT,'SUMMARY.txt'),JSON.stringify({summary:report.summary,limitations:report.limitations},null,2));
  console.log('FIX82_MATRIX '+JSON.stringify({group:GROUP,summary:report.summary,complete:report.complete,keyboardInventory:report.keyboardInventory?.visible}).slice(0,22000));
  await browser.close().catch(()=>{});
  if(!report.complete)process.exitCode=2;
 }
}
main().catch(e=>{console.error('FATAL',e);process.exitCode=2});
