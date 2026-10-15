'use strict';
// Fix71 diagnostic against actual Fix70 Emscripten/Ruffle output.
// Uses native control bounds, never shell-specific guesswork.
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT=path.resolve(process.env.UI_TEST_OUT||'fix71-probe');
fs.mkdirSync(OUT,{recursive:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const report={started:new Date().toISOString(),steps:[],errors:[],failedRequests:[],console:[],createWorldControls:[],moreOptionsControls:[],worldStarted:false};
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
  const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US',deviceScaleFactor:1});
  const history=[];
  page.on('console',m=>{
    const o={at:Date.now(),type:m.type(),text:m.text()};
    history.push(o);
    if(report.console.length<6000)report.console.push(o);
  });
  page.on('pageerror',e=>report.errors.push(String(e.stack||e)));
  page.on('crash',()=>report.errors.push('Browser crashed'));
  page.on('requestfailed',r=>report.failedRequests.push({url:r.url(),reason:r.failure()?.errorText}));
  page.on('dialog',d=>d.dismiss().catch(()=>{}));
  const tick=(label,extra={})=>{report.steps.push({at:new Date().toISOString(),label,...extra});console.log('FIX71: '+label+' '+JSON.stringify(extra).slice(0,1200));};
  async function snap(label){try{await page.screenshot({path:path.join(OUT,label+'.png'),timeout:30000});}catch(e){report.errors.push('screenshot '+label+': '+e);}}
  async function state(){return page.evaluate(()=>({
    root:globalThis.LCEWebIggy?.debugState?.().root,
    players:(globalThis.LCEWebIggy?.debugState?.().players||[]).filter(x=>x.visible).map(x=>x.filename),
    help:[...document.querySelectorAll('.lce-webiggy-menu-help-panel')].filter(e=>getComputedStyle(e).visibility==='visible').map(e=>({text:e.dataset.lceText,rect:e.getBoundingClientRect().toJSON()})),
    scale9:[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].filter(e=>getComputedStyle(e).visibility==='visible').map(e=>({key:e.dataset.lcePanelKey,rect:e.getBoundingClientRect().toJSON()}))
  }));}
  async function openScene(k){
    const q=await page.evaluate(k=>Module._lce_web_test_open_ui(k),k).catch(e=>'error: '+e);
    if(q!==1)return {requested:q,result:q};
    let result=-99;
    for(let t=0;t<80;t++){await delay(250);result=await page.evaluate(()=>Module._lce_web_test_last_ui_result()).catch(()=>-500);if(result!==-99)break;}
    await delay(1200);return {requested:q,result};
  }
  function controls(after){
    const found=[];
    for(const x of history.filter(h=>h.at>=after&&h.text.includes('[ui-test-control]'))){
      const m=x.text.match(/scene=(\d+) id=(-?\d+) type=(\d+) shown=(\d+) valid=(\d+) rect=(-?\d+),(-?\d+),(-?\d+),(-?\d+) name=(.+)$/);
      if(!m)continue;
      found.push({scene:+m[1],id:+m[2],type:+m[3],shown:m[4]==='1',valid:m[5]==='1',x:+m[6],y:+m[7],w:+m[8],h:+m[9],name:m[10]});
    }
    return [...new Map(found.map(x=>[x.scene+':'+x.id,x])).values()];
  }
  async function clickControl(c){
    const s=await state();const r=s.root;
    if(!r||!c||!c.valid||!c.shown)throw new Error('Missing valid control or root: '+JSON.stringify({r,c}));
    const x=r.x+(c.x+c.w/2)*r.width/1280;
    const y=r.y+(c.y+c.h/2)*r.height/720;
    await page.mouse.click(x,y);
    tick('clicked '+c.name,{stage:{x:c.x+c.w/2,y:c.y+c.h/2},client:{x,y}});
  }
  async function waitPlayer(name,limit=15000){
    const end=Date.now()+limit;
    do{const s=await state();if(s.players.some(x=>x.includes(name)))return s;await delay(400);}while(Date.now()<end);
    return await state();
  }
  try{
    await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
    await delay(34000);
    tick('boot',{ui:await state()});
    const start=Date.now();const opened=await openScene(12);await delay(1200);
    report.createWorldControls=controls(start);
    tick('createworld-native-controls',{opened,controls:report.createWorldControls});
    await snap('01-createworld');
    const more=report.createWorldControls.find(c=>c.shown&&c.valid&&/MoreOptions/i.test(c.name));
    const create=report.createWorldControls.find(c=>c.shown&&c.valid&&/Create.*World|NewWorld|StartWorld/i.test(c.name));
    tick('control-selection',{more,create});
    if(more){
      const t=Date.now();await clickControl(more);
      let s=await waitPlayer('LaunchMoreOptionsMenu',20000);
      await delay(2300);s=await state();
      report.moreOptionsControls=controls(t);
      tick('moreoptions-after-player',{ui:s,controls:report.moreOptionsControls});
      await snap('02-moreoptions-settled');
      await delay(4500);tick('moreoptions-later',{ui:await state()});await snap('03-moreoptions-7sec');
      await page.keyboard.press('Escape');await delay(2300);
      tick('after-return',{ui:await state()});
      await snap('04-return-createworld');
    } else {
      tick('moreoptions-control-missing',{available:report.createWorldControls.map(x=>x.name)});
    }
    // Fresh page avoids residual overlays and stale cursor/controller state.
    await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1&round=world',{waitUntil:'domcontentloaded',timeout:120000});
    await delay(34000);
    const b=Date.now();const reopened=await openScene(12);await delay(1200);
    const cc=controls(b);tick('world-controls',{reopened,controls:cc});
    const target=cc.find(c=>c.shown&&c.valid&&/Create.*World|NewWorld|StartWorld/i.test(c.name));
    if(target){
      await clickControl(target);
      await delay(10000);tick('10sec-post-create',{ui:await state()});await snap('05-after-create-10sec');
      let lastResult=null;
      const started=Date.now();
      while(Date.now()-started<175000){
        const probe=await openScene(20);
        lastResult=probe;
        if(probe.result===1||probe.result===0){report.worldStarted=true;report.inventoryProbe=probe;tick('world-live',{probe});await snap('06-world-inventory');break;}
        if(Date.now()-started>45000&&!report.probe45){report.probe45=probe;tick('world-45s',{probe,ui:await state()});await snap('06-world-45sec');}
        await delay(1800);
      }
      if(!report.worldStarted)tick('world-not-live',{lastResult,ui:await state()});
    }else tick('world-create-button-missing',{nativeControls:cc});
  }catch(e){report.errors.push('HARNESS: '+String(e.stack||e));tick('exception',{message:String(e)});}
  finally{
    report.finished=new Date().toISOString();
    fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
    fs.writeFileSync(path.join(OUT,'summary.txt'),JSON.stringify({
      worldStarted:report.worldStarted,
      selectedCreateWorld:report.createWorldControls.filter(c=>/Create|NewWorld|MoreOptions/i.test(c.name)),
      moreOptions:report.steps.filter(s=>/moreoptions|control-selection|world-live|world-not-live|missing/.test(s.label)),
      errors:report.errors,
      failedRequests:report.failedRequests
    },null,2));
    await browser.close().catch(()=>{});
  }
})();
