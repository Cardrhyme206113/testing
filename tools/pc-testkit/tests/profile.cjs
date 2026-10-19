'use strict';
/*
 * LOCAL, HEADED, MANUAL GAMEPLAY profiler.
 * This deliberately does not inject fake menus or simulate scene state.
 * It measures the original SWF-based UI and native WebAssembly game.
 */
const {chromium} = require('playwright-core');
const fs = require('fs');
const path = require('path');
const {spawn} = require('child_process');
const readline = require('readline');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const ROOT = path.resolve(__dirname, '..');
const REPORTS = path.join(ROOT, 'reports');
const URL = 'http://127.0.0.1:8765/lce_web_demo.html?lce_ui_test=1';
fs.mkdirSync(REPORTS, {recursive: true});
const rl = readline.createInterface({input: process.stdin, output: process.stdout});
const question = message => new Promise(resolve => rl.question(message, resolve));
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const prefix = path.join(REPORTS, 'lce-pc-' + stamp);
const result = {started: new Date().toISOString(), url: URL, computer: process.platform,
  browser: '', phases: [], snapshots: [], screenshots: [], errors: [], notes: []};

async function isOnline() {
  try {
    const r = await fetch('http://127.0.0.1:8765/');
    return r.ok;
  } catch { return false; }
}
async function launchServer() {
  if (await isOnline()) return null;
  const commands = process.platform === 'win32'
    ? [['py', ['-3','server.py','--no-browser']], ['python',['server.py','--no-browser']]]
    : [['python3',['server.py','--no-browser']], ['python',['server.py','--no-browser']]];
  for (const [command,args] of commands) {
    let child;
    try {
      child = spawn(command, args, {cwd:ROOT, windowsHide:true, stdio:['ignore','pipe','pipe']});
    } catch {continue;}
    let err = '';
    child.on('error', e => {err += ' '+e.message;});
    child.stderr.on('data', v => {err += v.toString().slice(0,600)});
    child.stdout.on('data', v => {if (v.toString().includes('local-only server'))console.log('Local server ready')});
    for(let i=0;i<35;i++) {
      await sleep(250);
      if (await isOnline()) return child;
      if (child.exitCode !== null) break;
    }
    if (child.exitCode === null) child.kill();
    console.log('Server attempt:',command,err.slice(0,300));
  }
  throw Error('Could not launch local Python server. Install Python 3 or run START-GAME.cmd.');
}
async function startBrowser() {
  for (const channel of ['msedge','chrome']) {
    try {
      const browser = await chromium.launch({channel,headless:false,
        args:['--enable-precise-memory-info','--disable-background-timer-throttling',
              '--disable-backgrounding-occluded-windows']});
      return {browser,channel};
    } catch(e) {console.log('Browser',channel,'not found:',e.message.split('\n')[0]);}
  }
  throw Error('Edge or Chrome not detected. Install one and retry.');
}
async function patchInstrumentation(page) {
  await page.addInitScript(() => {
    const memories = [], seen = new WeakSet();
    globalThis.__lceLocalMemories = memories;
    function capture(res,type) {
      const inst = res?.instance || res;
      const mem = inst?.exports?.memory;
      if (mem instanceof WebAssembly.Memory && !seen.has(mem)) {
        seen.add(mem);
        memories.push({id:memories.length + 1, type, initialMiB:mem.buffer.byteLength / 1048576,
          ref:new WeakRef(mem)});
      }
    }
    const instantiate = WebAssembly.instantiate;
    WebAssembly.instantiate=async function(...args) {
      const r=await instantiate.apply(this,args); capture(r,'instantiate'); return r;
    };
    if (WebAssembly.instantiateStreaming) {
      const instantiateStreaming=WebAssembly.instantiateStreaming;
      WebAssembly.instantiateStreaming=async function(...args) {
        const r=await instantiateStreaming.apply(this,args); capture(r,'instantiateStreaming'); return r;
      };
    }
    globalThis.__lceLocalFrameProbe = {
      recording:false, windows:[], samples:[], last:0, visible:new Map(),
      collectStats(){this.samples.push({at:performance.now(),frameCount:this.windows.length})}
    };
    function raf(now) {
      const q=globalThis.__lceLocalFrameProbe;
      if(q?.recording && q.last > 0) {
        q.windows.push(now-q.last);
        if (q.windows.length>3000) q.windows.shift();
      }
      q.last=now;
      requestAnimationFrame(raf);
    }
    requestAnimationFrame(raf);
  });
}
async function activateRuffleProbe(page) {
  return await page.evaluate(() => {
    const host=globalThis.LCEWebIggy;
    const q=globalThis.__lceLocalFrameProbe;
    if (!host || !q || typeof host.tickPlayer!=='function') return false;
    if (q.instrumented) return true;
    const original=host.tickPlayer;
    q.tickCalls=0;q.hiddenCalls=0;q.totalTickMs=0;q.hiddenTickMs=0;q.tickMaxMs=0;
    function refresh(){
      try {
        q.visible=new Map((host.debugState()?.players||[]).map(v=>[v.id,v.visible]));
      }catch(_){}
    }
    refresh();
    q.refreshTimer=setInterval(refresh,1000);
    host.tickPlayer=function(id){
      if (!q.recording) return original.apply(this,arguments);
      const isHidden=q.visible.get(id)===false;
      const now=performance.now();
      try {return original.apply(this,arguments);}
      finally {
        const ms=performance.now()-now;
        q.tickCalls++;q.totalTickMs+=ms;
        if(isHidden){q.hiddenCalls++;q.hiddenTickMs+=ms;}
        q.tickMaxMs=Math.max(q.tickMaxMs,ms);
      }
    };
    q.instrumented=true;
    return true;
  });
}
async function currentState(page) {
  return page.evaluate(() => {
    const all=globalThis.LCEWebIggy?.debugState?.()?.players||[];
    const active=all.filter(p=>p.visible);
    const mem=(globalThis.__lceLocalMemories||[]).map(p=>{
      const object=p.ref.deref();
      return {id:p.id,type:p.type,initialMiB:p.initialMiB,
        live:!!object,nowMiB:object ? object.buffer.byteLength/1048576 : null};
    });
    return {
      players:all.length,active:active.length,hidden:all.length-active.length,
      activeNames:active.map(p=>p.filename),allNames:all.map(p=>p.filename),
      ruffleElements:document.querySelectorAll('ruffle-player').length,
      domElements:document.querySelectorAll('*').length,
      mem,jsHeapMiB:performance.memory?.usedJSHeapSize/1048576 ?? null,
      crossOriginIsolated:crossOriginIsolated
    };
  });
}
async function perfMetrics(cdp) {
  const r=await cdp.send('Performance.getMetrics');
  const m=Object.fromEntries(r.metrics.map(x=>[x.name,x.value]));
  return {nodes:m.Nodes,taskDuration:m.TaskDuration,jsHeapMiB:m.JSHeapUsedSize/1048576,
    documents:m.Documents};
}
function percentiles(input) {
  const s=input.filter(x=>Number.isFinite(x)&&x>0).sort((a,b)=>a-b);
  if(!s.length)return {n:0,p50:null,p95:null,p99:null};
  const q=p=>Math.round(s[Math.floor((s.length-1)*p)]*100)/100;
  return {n:s.length,p50:q(.5),p95:q(.95),p99:q(.99)};
}
async function snap(page,cdp,label,doGC=false) {
  if(doGC) {
    try {await cdp.send('HeapProfiler.collectGarbage');}catch(e){result.notes.push('GC unavailable: '+e.message);}
    await sleep(250);
  }
  const data={label,time:new Date().toISOString(),...await currentState(page),cdp:await perfMetrics(cdp)};
  result.snapshots.push(data);
  return data;
}
async function timedPhase(page,cdp,name,seconds=15) {
  await page.bringToFront();
  await sleep(1200);
  const m0=await perfMetrics(cdp);
  await page.evaluate(()=>{
    const q=globalThis.__lceLocalFrameProbe;
    q.recording=false;q.windows.length=0;q.last=0;
    q.tickCalls=0;q.hiddenCalls=0;q.totalTickMs=0;q.hiddenTickMs=0;q.tickMaxMs=0;
    q.recording=true;
  });
  const start=Date.now();
  await sleep(seconds*1000);
  const local=await page.evaluate(()=>{
    const q=globalThis.__lceLocalFrameProbe;
    q.recording=false;
    return {windowTimes:q.windows.slice(),tickCalls:q.tickCalls,hiddenCalls:q.hiddenCalls,
      tickTotalMs:q.totalTickMs,hiddenTickMs:q.hiddenTickMs,tickMaxMs:q.tickMaxMs};
  });
  const m1=await perfMetrics(cdp);
  const state=await currentState(page);
  const phase={name,elapsedSeconds:(Date.now()-start)/1000,
    raf:percentiles(local.windowTimes),tickCalls:local.tickCalls,
    hiddenTickCalls:local.hiddenCalls,
    hiddenTickRatio:local.tickCalls?local.hiddenCalls/local.tickCalls:0,
    tickCpuMs:local.tickTotalMs,hiddenTickCpuMs:local.hiddenTickMs,
    maxTickMs:local.tickMaxMs,taskDurationSeconds:m1.taskDuration-m0.taskDuration,
    players:state.players,hiddenPlayers:state.hidden,visibleScenes:state.activeNames,
    wasm:state.mem,jsHeapMiB:state.jsHeapMiB};
  result.phases.push(phase);
  console.log('\n  '+name+':');
  console.log('  requestAnimationFrame p50 = '+phase.raf.p50+'ms, p95 = '+phase.raf.p95+
    'ms (count='+phase.raf.n+')');
  console.log('  WebIggy ticks = '+phase.tickCalls+', hidden = '+phase.hiddenTickCalls+
    ', hidden CPU = '+phase.hiddenTickCpuMs.toFixed(1)+'ms');
  console.log('  Players = '+phase.players+', hidden players = '+phase.hiddenPlayers+
    ', WASM memory MiB = '+phase.wasm.map(x=>x.id+':'+x.nowMiB).join(' / '));
  return phase;
}
function diff(a,b) {
  if(!a||!b)return {};
  return {
    frameMedianDeltaMs:b.raf.p50-a.raf.p50,
    frameP95DeltaMs:b.raf.p95-a.raf.p95,
    hiddenTickCpuDeltaMs:b.hiddenTickCpuMs-a.hiddenTickCpuMs,
    taskDurationDeltaSeconds:b.taskDurationSeconds-a.taskDurationSeconds,
    playersDelta:b.players-a.players
  };
}
async function main() {
  let child=null,browser=null;
  try {
    child=await launchServer();
    const launch=await startBrowser();
    browser=launch.browser;result.browser=launch.channel;
    const context=await browser.newContext({viewport:{width:1366,height:900}});
    const page=await context.newPage();
    await patchInstrumentation(page);
    page.on('pageerror',e=>result.errors.push({kind:'pageerror',msg:String(e).slice(0,500)}));
    page.on('crash',()=>result.errors.push({kind:'crash'}));
    page.on('console',m=>{if(m.type()==='error'&&result.errors.length<120)
      result.errors.push({kind:'console',msg:m.text().slice(0,400)})});
    const cdp=await context.newCDPSession(page);await cdp.send('Performance.enable');
    await page.goto(URL,{waitUntil:'domcontentloaded',timeout:120000});
    console.log('\nGAME WINDOW OPEN. Create or load a real single-player world yourself.');
    console.log('Keep the game window visible (do not minimize) while measuring.');
    console.log('Do not continuously move or load new chunks during the timed phases.');
    await question('\nOnce in the world with menus CLOSED, press Enter HERE to start baseline... ');
    if(!await activateRuffleProbe(page))throw Error('WebIggy tickPlayer was not ready. Open a world and retry.');
    const startState=await snap(page,cdp,'before-baseline',true);
    if(!startState.crossOriginIsolated)throw Error('Site is not crossOriginIsolated. Check localhost headers.');
    const base=await timedPhase(page,cdp,'BASELINE',15);
    await snap(page,cdp,'baseline-end',true);
    await question('\nNow open/close real Debug, Inventory, Settings and Controls menus 15-30 times. '+
      'Return HERE and press Enter when ALL menus are CLOSED... ');
    await snap(page,cdp,'post-ui-before-gc',false);
    await snap(page,cdp,'post-ui-after-gc',true);
    const after=await timedPhase(page,cdp,'AFTER-MENUS',15);
    await snap(page,cdp,'after-test',true);
    result.comparisons={baselineVersusPost:diff(base,after)};
    console.log('\nBASELINE -> POST: '+JSON.stringify(result.comparisons.baselineVersusPost));
    const again=(await question('\nDo another 15-30 UI cycles to check plateau? [Y/n] ')).trim().toLowerCase();
    if(again!=='n') {
      await question('\nRepeat the same menu sequence, close everything, then press Enter HERE... ');
      await snap(page,cdp,'round2-before-gc',false);await snap(page,cdp,'round2-after-gc',true);
      const second=await timedPhase(page,cdp,'AFTER-MENUS-ROUND2',15);
      result.comparisons.firstPostVersusSecondPost=diff(after,second);
      await snap(page,cdp,'round2-end',true);
    }
    try {
      const img=prefix+'-final.png';
      await page.screenshot({path:img,timeout:12000});
      result.screenshots.push(path.basename(img));
    }catch(e){result.notes.push('Screenshot: '+e.message.slice(0,300));}
    console.log('\nProfile complete. Baseline p50 '+base.raf.p50+'ms; after p50 '+after.raf.p50+'ms.');
  }catch(e) {result.errors.push({kind:'fatal',msg:String(e.stack||e)});console.error('\nTEST FAILED:',e.message);}
  finally {
    result.finished=new Date().toISOString();
    fs.writeFileSync(prefix+'.json',JSON.stringify(result,null,2));
    let summary=['Minecraft LCE Fix113 local PC profile','Browser: '+result.browser,
      'Start: '+result.started,'Finish: '+result.finished,'',
      'SCENE PLAYERS ARE FLASH/SWF INSTANCES, NOT NETWORK CLIENTS.',''];
    for(const p of result.phases) {
      summary.push(p.name+': RAF p50='+p.raf.p50+' ms, p95='+p.raf.p95+' ms, '+
        'total ticks='+p.tickCalls+', hidden ticks='+p.hiddenTickCalls+', '+
        'hidden CPU='+p.hiddenTickCpuMs.toFixed(1)+' ms');
      summary.push('  WASM allocations: '+p.wasm.map(x=>x.id+':'+x.nowMiB+' MiB').join(', '));
      summary.push('  SWF instances='+p.players+', hidden='+p.hiddenPlayers);
    }
    summary.push('','Comparisons:',JSON.stringify(result.comparisons||{},null,2));
    summary.push('','Errors: '+result.errors.length);
    fs.writeFileSync(prefix+'.txt',summary.join('\r\n'));
    console.log('Saved: '+prefix+'.json');
    console.log('Saved: '+prefix+'.txt');
    try{await browser?.close()}catch(_){}
    try{child?.kill()}catch(_){}
    rl.close();
    if(result.errors.some(x=>x.kind==='fatal'))process.exitCode=1;
  }
}
main();
