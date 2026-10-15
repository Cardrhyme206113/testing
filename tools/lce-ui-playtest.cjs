/* LCE-Web UI regression runner, fix64.
 * Requires the *fix64* browser build (not fix63), and the original LCE SWFs.
 * npm install --no-save playwright && npx playwright install chromium
 * UI_TEST_URL='http://127.0.0.1:8000/lce_web_demo.html' node tools/playtest_ui_matrix.cjs
 * Screenshots, structured control audit and unhandled errors go to UI_TEST_OUT.
 * Test hook is opt-in via ?lce_ui_test=1 and queues scene operations on the
 * real game thread. No fabricated Flash-only scenes or fake tile inventories.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');
const OUT = path.resolve(process.env.UI_TEST_OUT || 'ui-playtest-results');
const BASE = process.env.UI_TEST_URL || 'http://127.0.0.1:8000/lce_web_demo.html';
const SCENE_ENUM = {
  0:3, 1:15, 2:24, 3:19, 4:20, 5:21, 6:22, 7:23,
  8:17, 9:18, 10:41, 11:26, 12:29,
  20:11, 21:6, 22:7, 23:8, 24:9, 25:49, 26:50,
  27:12, 28:54, 29:45, 30:5
};
const FILES = [
  [0,'MainMenu'],[1,'HelpAndOptions'],[2,'Settings'],[3,'SettingsOptions'],
  [4,'SettingsAudio'],[5,'SettingsControls'],[6,'SettingsGraphics'],
  [7,'SettingsUI'],[8,'HowToPlay'],[9,'Controls'],[10,'SkinSelect'],
  [11,'Credits'],[12,'CreateWorld'],
  [20,'Inventory'],[21,'Crafting2x2'],[22,'Crafting3x3'],[23,'Furnace'],
  [24,'Chest'],[25,'Enchanting'],[26,'Brewing'],[27,'Dispenser'],
  [28,'Anvil'],[29,'Creative'],[30,'Pause']
];
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const slug=s=>s.replace(/[^a-z0-9-]/gi,'_').slice(0,90);
fs.mkdirSync(OUT,{recursive:true});
const report={created:new Date().toISOString(),scenes:[],exceptions:[],console:[],failedRequests:[],worldStarted:false,controlsTested:0,limitations:[
  'A screenshot cannot automatically establish pixel parity without reference images.',
  'Container scene constructors use real player/tile/inventory objects, but crafted tiles are not installed in the world.',
  'Trading is excluded: a valid Merchant and offers are required.',
  'Clicking a control without a visible change is recorded for review, not presumed functional.'
]};
const browserArgs=['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader',
  '--use-gl=angle','--use-angle=swiftshader','--autoplay-policy=no-user-gesture-required',
  '--disable-dev-shm-usage','--lang=en-US'];
async function run(){
  const browser=await chromium.launch({headless:true,args:browserArgs});
  const context=await browser.newContext({viewport:{width:1280,height:800},locale:'en-US',deviceScaleFactor:1});
  let page=null;
  const history=[];
  async function newPage(){
    if(page)await page.close().catch(()=>{});
    page=await context.newPage();
    page.on('console',m=>{
      const msg=m.text();history.push({time:Date.now(),type:m.type(),text:msg.slice(0,1200)});
      if(report.console.length<5000) report.console.push({type:m.type(),text:msg.slice(0,800)});
    });
    page.on('pageerror',e=>report.exceptions.push(String(e.stack||e).slice(0,4000)));
    page.on('crash',()=>report.exceptions.push('Chromium renderer process crashed'));
    page.on('requestfailed',r=>report.failedRequests.push({url:r.url(),error:r.failure()?.errorText}));
    page.on('dialog',d=>d.dismiss().catch(()=>{}));
    const u=new URL(BASE);u.searchParams.set('lce_ui_test','1');
    await page.goto(u.toString(),{waitUntil:'domcontentloaded',timeout:120000});
    await pause(Number(process.env.UI_BOOT_MS||35000));
  }
  async function movie(){
    return page.evaluate(()=>globalThis.LCEWebIggy?.debugState?.()||null).catch(()=>null);
  }
  async function open(kind){
    const start=Date.now();
    const r=await page.evaluate(k=>{
      const m=globalThis.Module;
      return m&&typeof m._lce_web_test_open_ui==='function' ? m._lce_web_test_open_ui(k):-100;
    },kind).catch(()=>-101);
    if(r!==1)return {request:r,result:r};
    let result=-99;
    do{
      await pause(250);
      result=await page.evaluate(()=>globalThis.Module?._lce_web_test_last_ui_result?.()??-102).catch(()=>-103);
    }while(result===-99 && Date.now()-start<15000);
    await pause(1100);
    return {request:r,result};
  }
  async function snapshot(filename){
    const p=slug(filename)+'.png';
    await page.screenshot({path:path.join(OUT,p),timeout:25000});
    return p;
  }
  function controlsFor(id,seenSince){
    const result=[];
    for(const e of history){
      if(e.time<seenSince)continue;
      const s=e.text;
      if(!s.includes('[ui-test-control]'))continue;
      const m=s.match(/scene=(\d+) id=(-?\d+) type=(\d+) shown=(\d+) valid=(\d+) rect=(-?\d+),(-?\d+),(-?\d+),(-?\d+) name=(.+)$/);
      if(!m||!m[1]||+m[1]!==id)continue;
      result.push({id:+m[2],type:+m[3],shown:+m[4]===1,valid:+m[5]===1,
        x:+m[6],y:+m[7],w:+m[8],h:+m[9],name:m[10]});
    }
    return [...new Map(result.map(v=>[v.id+':'+v.name,v])).values()];
  }
  async function clickControl(control){
    const state=await movie();
    const root=state?.root || {x:0,y:80,width:1280,height:720};
    // Our stage is authored as 1280x720. Derive DOM coords from WebIggy root;
    // do not hardcode the Emscripten shell's 80px header offset.
    const x=root.x+(control.x+control.w/2)*(root.width/1280);
    const y=root.y+(control.y+control.h/2)*(root.height/720);
    await page.mouse.click(x,y,{timeout:5000});
  }
  async function testScene([kind,title],sweep=true){
    const before=report.exceptions.length, began=Date.now();
    const scene={kind,title,opened:null,before:null,controls:[],tests:[],exceptions:[],status:'unknown'};
    report.scenes.push(scene);
    try{
      if(kind>=20){await page.keyboard.press('Escape');await pause(1050);}
      scene.opened=await open(kind);
      if(kind>=20 && scene.opened.result===0){
        await page.keyboard.press('Escape');await pause(1300);
        scene.opened=await open(kind);
      }
      if(scene.opened.result!==1){scene.status='unavailable';return;}
      await pause(650);
      scene.before=await snapshot(`${kind}-${title}-initial`);
      scene.controls=controlsFor(SCENE_ENUM[kind],began);
      // getSceneType enum differs from the numeric test selector; some scenes
      // have already reported their controls to stderr. If no controls are
      // found, record it rather than invent positions or success.
      if(!scene.controls.length){
        const all=history.filter(e=>e.time>=began&&e.text.includes('[ui-test-control]'));
        for(const e of all){
          const m=e.text.match(/scene=(\d+)/);if(m){scene.controlSceneId=+m[1];break;}
        }
        if(scene.controlSceneId!==undefined)scene.controls=controlsFor(scene.controlSceneId,began);
      }
      if(sweep){
        for(const c of scene.controls){
          if(!c.shown || !c.valid || ![1,2,3,17,18,19,20].includes(c.type) || c.w<8 || c.h<8)continue;
          // World creation and exit are stateful; tested separately.
          if(kind===12 && /NewWorld|CreateWorld/i.test(c.name))continue;
          if(kind===0 && /Exit/i.test(c.name))continue;
          const step={id:c.id,type:c.type,name:c.name,status:'unknown'};
          scene.tests.push(step);
          try{
            if(kind>=20){await page.keyboard.press('Escape');await pause(850);}
            let r=await open(kind);
            if(r.result!==1){step.status='reopen-failed';step.result=r;break;}
            const prior=await movie();
            const errorsBefore=report.exceptions.length;
            await clickControl(c);
            await pause(650);
            const after=await movie();
            step.afterScreenshot=await snapshot(`${kind}-${title}-${c.id}-${c.name}-clicked`);
            step.beforeScenes=prior?.players?.filter(p=>p.visible).map(p=>p.filename);
            step.afterScenes=after?.players?.filter(p=>p.visible).map(p=>p.filename);
            step.newErrors=report.exceptions.slice(errorsBefore);
            step.status=step.newErrors.length?'error':'needs-visual-review';
            report.controlsTested++;
          }catch(e){step.status='exception';step.error=String(e);}
        }
      }
      scene.status=report.exceptions.length>before?'browser-error':'captured';
    }catch(e){scene.status='harness-error';scene.error=String(e.stack||e);}
    finally{scene.exceptions=report.exceptions.slice(before);fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));}
  }
  try{
    await newPage();
    for(const row of FILES.filter(([k])=>k<=12)) await testScene(row,true);
    // Use a fresh page for the heavyweight world-start transition. The
    // frontend sweep may have left scene stacks/overlay state behind.
    await newPage();
    const creation=await open(12);
    if(creation.result===1){
      await snapshot('12-CreateWorld-before-create');
      await page.mouse.click(640,646);
      await pause(1600);
      await snapshot('12-CreateWorld-after-create');
      await page.keyboard.press('Enter'); // privilege confirmation if present
      const start=Date.now();
      // Poll the real C++ game-start predicate indirectly through the public
      // test-scene bridge, not speculative console text.
      while(Date.now()-start<180000){
        const probe=await open(20);
        if(probe.result===1){
          report.worldStarted=true;
          await snapshot('20-World-live-inventory-probe');
          break;
        }
        await pause(2500);
      }
    }
    if(report.worldStarted){
      for(const row of FILES.filter(([k])=>k>=20)) await testScene(row,true);
    }else report.limitations.push('World did not become live; in-game matrix skipped.');
  }finally{
    report.finished=new Date().toISOString();
    fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
    fs.writeFileSync(path.join(OUT,'summary.txt'),[
      `Scenes: ${report.scenes.length}, controls clicked: ${report.controlsTested}, world: ${report.worldStarted}`,
      ...report.scenes.map(x=>`${x.kind} ${x.title}: ${x.status}; controls=${x.controls.length}; buttons=${x.tests.length}`),
      `Uncaught browser errors: ${report.exceptions.length}`,...report.exceptions.slice(-30)
    ].join('\n'));
    await browser.close().catch(()=>{});
  }
}
run().catch(e=>{console.error(e);process.exitCode=1});