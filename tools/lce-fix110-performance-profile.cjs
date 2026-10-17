'use strict';
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const dir='fix110-profile';fs.mkdirSync(dir,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
  const cdp=await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  const result={build:'Fix109 baseline unchanged native WASM and Ruffle',exceptions:[],errors:[],samples:[],events:[],fatal:[]};
  page.on('pageerror',e=>result.exceptions.push(String(e.stack||e)));
  page.on('crash',()=>result.fatal.push('Chromium renderer crashed'));
  page.on('console',m=>{if(m.type()==='error'&&result.errors.length<100)result.errors.push(m.text().slice(0,300))});
  async function state(){
    const p=await page.evaluate(()=>{
      const host=globalThis.LCEWebIggy;
      const debug=host?.debugState?.()||{};
      const profile=globalThis.__lceProfiler||{};
      const counts=profile.counts||{};
      return {scenes:(debug.players||[]).filter(x=>x.visible).map(x=>x.filename),
        players:(debug.players||[]).length,hidden:(debug.players||[]).filter(x=>!x.visible).length,
        canvas:document.querySelectorAll('canvas').length,
        ruffle:document.querySelectorAll('ruffle-player').length,
        wasmBytes:globalThis.Module?.HEAPU8?.buffer?.byteLength??null,
        jsHeapReported:performance.memory?.usedJSHeapSize??null,
        totalTickCalls:counts.total||0,hiddenTickCalls:counts.hidden||0,
        cumulativeTickMs:counts.milliseconds||0,raf:profile.raf||0,
        frameMsP95:(()=>{const a=(profile.frameTimes||[]).slice().sort((x,y)=>x-y);return a.length?a[Math.floor(.95*(a.length-1))]:null})(),
        lastUi:globalThis.Module?._lce_web_test_last_ui_result?.()??null
      };
    });
    const metrics=await cdp.send('Performance.getMetrics');
    const map=Object.fromEntries(metrics.metrics.map(x=>[x.name,x.value]));
    let counters={};
    try{counters=await cdp.send('Memory.getDOMCounters')}catch(_){}
    return {...p,cdpJsHeap:map.JSHeapUsedSize,cdpTaskDuration:map.TaskDuration,
      cdpNodes:map.Nodes,cdpDocuments:map.Documents,domCounters:counters};
  }
  try{
    await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
    let ready=false;
    for(let i=0;i<80;i++){await sleep(1000);let s=await state();if(s.scenes.includes('MainMenu720.swf')&&typeof s.lastUi==='number'){ready=true;break}}
    if(!ready)throw Error('No main menu');
    await page.evaluate(()=>{
      const host=globalThis.LCEWebIggy,orig=host.tickPlayer;
      const state={counts:{total:0,hidden:0,milliseconds:0},raf:0,frameTimes:[],visible:new Map()};
      globalThis.__lceProfiler=state;
      let prev=0;function frame(now){if(prev){state.frameTimes.push(now-prev);if(state.frameTimes.length>1200)state.frameTimes.shift()}prev=now;state.raf++;requestAnimationFrame(frame)}requestAnimationFrame(frame);
      const refresh=()=>{state.visible=new Map(host.debugState().players.map(x=>[x.id,x.visible]))};
      refresh();setInterval(refresh,2000);
      host.tickPlayer=function(id){
        state.counts.total++;
        if(state.visible.get(id)===false)state.counts.hidden++;
        const t=performance.now();try{return orig.apply(this,arguments)}finally{state.counts.milliseconds+=performance.now()-t}
      };
    });
    result.events.push({phase:'main',...await state()});
    const req=await page.evaluate(()=>Module._lce_web_test_open_ui(12));
    result.events.push({phase:'createWorldRequest',req});
    let menu=false;
    for(let i=0;i<80;i++){await sleep(300);let s=await state();if(s.scenes.includes('CreateWorldMenu720.swf')&&s.lastUi===1){menu=true;break}}
    if(!menu)throw Error('CreateWorld native scene did not load');
    await page.mouse.click(640,605);
    await sleep(500);
    let world=false;
    for(let i=0;i<150;i++){
      await sleep(1000);const s=await state();
      if(i%30===0)result.events.push({phase:'generation',second:i,...s});
      if(i>8&&!s.scenes.includes('CreateWorldMenu720.swf')){
        const q=await page.evaluate(()=>Module._lce_web_test_open_ui(20));
        if(q===1){await sleep(500);if((await state()).lastUi===1){world=true;break}}
      }
    }
    if(!world)throw Error('World did not initialize');
    await page.keyboard.press('Backquote');await sleep(1000);
    result.events.push({phase:'worldReady',...await state()});
    const started=Date.now(),durationSec=240;
    for(let sec=0;sec<=durationSec;sec+=10){
      if(sec>0)await sleep(10000);
      const s=await state();
      result.samples.push({second:sec,...s});
      if(sec%60===0){
        await page.screenshot({path:path.join(dir,'gameplay-'+sec+'.png'),timeout:12000});
        console.log('LCE_PROFILE_PROGRESS',sec,JSON.stringify({players:s.players,hidden:s.hidden,ticks:s.totalTickCalls,hiddenTicks:s.hiddenTickCalls,cdpHeap:s.cdpJsHeap,wasm:s.wasmBytes,raf:s.raf}));
      }
      if(sec%30===10){await page.keyboard.down('KeyW');await sleep(300);await page.keyboard.up('KeyW')}
    }
    result.durationMs=Date.now()-started;
  }catch(e){result.fatal.push(String(e.stack||e))}
  finally{
    fs.writeFileSync(path.join(dir,'report.json'),JSON.stringify(result,null,2));
    console.log('FIX110_PROFILE',JSON.stringify({samples:result.samples.length,head:result.samples[0],tail:result.samples.at(-1),fatal:result.fatal,exceptionCount:result.exceptions.length}));
    await browser.close().catch(()=>{});
    if(result.fatal.length)process.exitCode=1;
  }
})();