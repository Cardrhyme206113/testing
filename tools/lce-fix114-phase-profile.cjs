'use strict';
const {chromium}=require('playwright'),fs=require('fs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const dir='fix114-profile'; fs.mkdirSync(dir,{recursive:true});
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--js-flags=--expose-gc']});
  const page=await browser.newPage({viewport:{width:1280,height:800}});
  const cdp=await page.context().newCDPSession(page);await cdp.send('Performance.enable');
  const out={run:'Fix114 phase-controlled instrumentation on Fix113 playable',samples:[],ui:[],errors:[],fatal:[],memoryStages:[]};
  page.on('pageerror',e=>out.errors.push('pageerror '+e));
  page.on('crash',()=>out.fatal.push('renderer crashed'));
  page.on('console',m=>{if(m.type()==='error'&&out.errors.length<100)out.errors.push(m.text().slice(0,500))});
  await page.addInitScript(()=>{
    const registry=[];let next=0;
    globalThis.__lceMem={registry};
    function register(res,kind) {
      const instance=res?.instance||res;
      const m=instance?.exports?.memory;
      if(m instanceof WebAssembly.Memory) {
        registry.push({id:++next,kind,ref:new WeakRef(m),initial:m.buffer.byteLength,createdAt:performance.now()});
      }
    }
    const old=WebAssembly.instantiate;
    WebAssembly.instantiate=async function(...args){const x=await old.apply(this,args);register(x,'instantiate');return x};
    if(WebAssembly.instantiateStreaming){
      const oldStream=WebAssembly.instantiateStreaming;
      WebAssembly.instantiateStreaming=async function(...args){const x=await oldStream.apply(this,args);register(x,'instantiateStreaming');return x};
    }
  });
  async function measure(tag,collect=false){
    const state=await page.evaluate(()=>{
      const host=globalThis.LCEWebIggy;
      const a=host?.debugState?.()?.players||[];
      const x=globalThis.__lcePerf||{};
      const arr=x.frameMs||[],sorted=[...arr].sort((a,b)=>a-b);
      const quant=q=>sorted.length?sorted[Math.floor((sorted.length-1)*q)]:null;
      const wasm=(globalThis.__lceMem?.registry||[]).map(y=>({...y,ref:undefined,live:!!y.ref.deref(),current:y.ref.deref()?.buffer.byteLength||0}));
      return {scenes:a.filter(y=>y.visible).map(y=>y.filename),players:a.length,hidden:a.filter(y=>!y.visible).length,
        ruffleElements:document.querySelectorAll('ruffle-player').length,
        domElements:document.querySelectorAll('*').length,
        wasm,frameCount:arr.length,rafP50:quant(.5),rafP95:quant(.95),rafP99:quant(.99),
        tickCount:x.total||0,hiddenTickCount:x.hidden||0,hiddenTickMs:x.hiddenMs||0,totalTickMs:x.tickMs||0,
        tickMaxMs:x.tickMax||0,phase:x.phase||'',jsHeap:performance.memory?.usedJSHeapSize||null,
        lastUi:globalThis.Module?._lce_web_test_last_ui_result?.()??null};
    });
    const m=await cdp.send('Performance.getMetrics');const perf=Object.fromEntries(m.metrics.map(x=>[x.name,x.value]));
    const sample={tag,...state,cdpNodes:perf.Nodes,cdpTaskDuration:perf.TaskDuration,cdpJsHeap:perf.JSHeapUsedSize};
    (collect?out.samples:out.ui).push(sample);
    console.log('FIX114_SNAPSHOT',JSON.stringify({tag,players:sample.players,hidden:sample.hidden,scenes:sample.scenes,memMiB:state.wasm.map(x=>(x.current/1048576).toFixed(2)),wasmIds:state.wasm.map(x=>x.id),rafP50:sample.rafP50,rafP95:sample.rafP95,ticks:sample.tickCount,hiddenTicks:sample.hiddenTickCount,hiddenMs:sample.hiddenTickMs,domNodes:sample.cdpNodes}));
    return sample;
  }
  async function startPhase(name) {
    await page.evaluate(name=>{
      const p=globalThis.__lcePerf;p.phase=name;p.frameMs=[];p.total=0;p.hidden=0;p.hiddenMs=0;p.tickMs=0;p.tickMax=0;
    },name);
    await measure(name+'-start');
  }
  async function phase(name,duration=15000) {
    await startPhase(name);
    await sleep(duration);
    await measure(name+'-end',true);
  }
  async function openNative(id,name) {
    const req=await page.evaluate(id=>globalThis.Module._lce_web_test_open_ui(id),id);
    for(let i=0;i<60;i++){
      await sleep(200);
      const s=await page.evaluate(()=>globalThis.Module?._lce_web_test_last_ui_result?.()??null);
      if(s!==-99){out.ui.push({tag:'nav '+name,req,result:s});return s===1}
    }
    out.ui.push({tag:'nav timeout '+name,req});return false;
  }
  async function gc(){try{await cdp.send('HeapProfiler.collectGarbage');await page.evaluate(()=>globalThis.gc?.());}catch(_){}}
  try{
    await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
    let ready=false;
    for(let i=0;i<90;i++){
      await sleep(1000);
      const q=await page.evaluate(()=>({a:globalThis.LCEWebIggy?.debugState?.()?.players||[],r:globalThis.Module?._lce_web_test_last_ui_result?.()}));
      if(q.a.some(y=>y.visible&&y.filename==='MainMenu720.swf')&&typeof q.r==='number'){ready=true;break}
    }
    if(!ready)throw Error('main menu not ready');
    const q=await openNative(12,'CreateWorld');
    if(!q)throw Error('CreateWorld native constructor failure');
    let menuReady=false;
    for(let k=0;k<75;k++){
      await sleep(250);
      const a=await page.evaluate(()=>globalThis.LCEWebIggy?.debugState?.()?.players||[]);
      if(a.some(y=>y.visible&&y.filename==='CreateWorldMenu720.swf')){menuReady=true;break}
    }
    out.ui.push({tag:'createworld-visible',ready:menuReady});
    await page.screenshot({path:dir+'/01-menu-before-click.png',timeout:12000}).catch(e=>out.errors.push('screenshot '+e));
    if(!menuReady)throw Error('CreateWorld native scene never became visible');
    await sleep(1500);await page.mouse.click(640,605);
    let world=false;
    for(let i=0;i<150;i++){
      await sleep(1000);
      const a=await page.evaluate(()=>globalThis.LCEWebIggy?.debugState?.()?.players||[]);
      const names=a.filter(y=>y.visible).map(y=>y.filename);
      if(i===10||i===60){out.ui.push({tag:'load-'+i,scenes:names});await page.screenshot({path:dir+'/load-'+i+'.png',timeout:12000}).catch(()=>{})}
      if(i===15&&names.includes('CreateWorldMenu720.swf'))await page.mouse.click(640,605);
      if(i>20&&!names.some(y=>/CreateWorldMenu|FullscreenProgress|Loading|Saving/.test(y))){
        await sleep(3000);
        const b=await page.evaluate(()=>globalThis.LCEWebIggy?.debugState?.()?.players||[]);
        if(!b.filter(y=>y.visible).some(y=>/CreateWorldMenu|FullscreenProgress|Loading|Saving/.test(y.filename))){world=true;break}
      }
    }
    if(!world)throw Error('world failed to load; last visible scenes: '+JSON.stringify((await page.evaluate(()=>globalThis.LCEWebIggy?.debugState?.()?.players||[])).filter(y=>y.visible).map(y=>y.filename)));
    await page.evaluate(()=>{
      const host=globalThis.LCEWebIggy;
      const old=host.tickPlayer;
      const p={phase:'',frameMs:[],total:0,hidden:0,hiddenMs:0,tickMs:0,tickMax:0,skipHidden:false,vis:new Map()};
      globalThis.__lcePerf=p;
      let at=0;
      const refresh=()=>{try{p.vis=new Map(host.debugState().players.map(x=>[x.id,x.visible]))}catch(_){}};
      refresh();
      setInterval(refresh,1000);
      host.tickPlayer=function(id){
        const hidden=p.vis.get(id)===false;
        p.total++;if(hidden)p.hidden++;
        const st=performance.now();
        try{if(hidden&&p.skipHidden)return;return old.apply(this,arguments)}
        finally{const duration=performance.now()-st;p.tickMs+=duration;if(hidden)p.hiddenMs+=duration;p.tickMax=Math.max(p.tickMax,duration)}
      };
      let last=0;
      function frame(now){if(last){p.frameMs.push(now-last);if(p.frameMs.length>700)p.frameMs.shift()}last=now;requestAnimationFrame(frame)}
      requestAnimationFrame(frame);
    });
    await gc();await phase('idle-control-a',16000);
    const ids=[{id:9,name:'controls'},{id:3,name:'settings-options'},{id:20,name:'inventory'},{id:30,name:'pause'}];
    for(let i=0;i<8;i++){
      const item=ids[i%ids.length];
      const ok=await openNative(item.id,item.name);
      await sleep(1000);
      await page.keyboard.press('Backquote');
      await sleep(550);
      out.ui.push({tag:'cycle '+i,name:item.name,ok});
      await measure('cycle-'+i);
    }
    await gc();await phase('idle-control-b',16000);
    await page.evaluate(()=>globalThis.__lcePerf.skipHidden=true);
    await phase('idle-skip-hidden',16000);
    const verify=await openNative(9,'controls-after-skip');
    out.ui.push({tag:'verify-controls-after-skip',ok:verify});
    await page.keyboard.press('Backquote');
    await sleep(1000);
    await gc();await phase('idle-skip-hidden-post-ui',16000);
    await page.evaluate(()=>globalThis.__lcePerf.skipHidden=false);
    await phase('idle-control-c',16000);
    await gc();await measure('final',true);
    const ends=out.samples.map(x=>({phase:x.phase,rafP50:x.rafP50,rafP95:x.rafP95,hiddenTickCount:x.hiddenTickCount,totalTicks:x.tickCount,hiddenMs:x.hiddenTickMs,wasmMiB:x.wasm.map(y=>y.current/1048576),nodes:x.cdpNodes,scenes:x.scenes,players:x.players}));
    console.log('FIX114_PHASES',JSON.stringify(ends));
  }catch(e){out.fatal.push(String(e.stack||e))}
  finally{fs.writeFileSync(dir+'/report.json',JSON.stringify(out,null,2));console.log('FIX114_RESULT',JSON.stringify({fatal:out.fatal,errors:out.errors.length,samples:out.samples.length,ui:out.ui.length}));await browser.close().catch(()=>{});if(out.fatal.length)process.exitCode=1}
})();
