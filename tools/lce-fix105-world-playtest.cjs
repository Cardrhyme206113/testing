'use strict';
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const OUT='fix105-evidence';fs.mkdirSync(OUT,{recursive:true});const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const b=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await b.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const r={binary:'Fix96',worldLoaded:false,events:[],samples:[],exceptions:[],fatal:[]};
 page.on('pageerror',e=>r.exceptions.push({message:e.message,stack:String(e.stack).slice(0,1600)}));
 page.on('crash',()=>r.fatal.push('Chromium PAGE CRASH'));
 page.on('dialog',d=>d.dismiss().catch(()=>{}));
 const st=()=>page.evaluate(()=>({scenes:(globalThis.LCEWebIggy?.debugState?.()?.players||[]).filter(x=>x.visible).map(x=>x.filename),lastUi:typeof Module?._lce_web_test_last_ui_result==='function'?Module._lce_web_test_last_ui_result():null,heap:performance.memory?.usedJSHeapSize??null,isolated:crossOriginIsolated,frames:(globalThis.__lceFrames||0)}));
 const snap=async name=>{try{await page.screenshot({path:path.join(OUT,name+'.png'),timeout:18000})}catch(e){r.fatal.push('screenshot '+name+': '+e)}};
 try{
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
  await page.evaluate(()=>{globalThis.__lceFrames=0;const f=()=>{globalThis.__lceFrames++;requestAnimationFrame(f)};requestAnimationFrame(f)});
  let hasMain=false;
  for(let t=0;t<75;t++){await sleep(1000);const s=await st();if(t%15===0)r.events.push({t,phase:'startup',...s});if(s.scenes.includes('MainMenu720.swf')){hasMain=true;break}}
  if(!hasMain)throw Error('MainMenu failed to load');
  await snap('01-main');
  const req=await page.evaluate(()=>Module._lce_web_test_open_ui(12));
  r.events.push({phase:'createWorldRequest',req});
  for(let t=0;t<90;t++){await sleep(500);const s=await st();if(s.scenes.includes('CreateWorldMenu720.swf')&&s.lastUi===1){r.events.push({phase:'createWorldMenu',...s});break}}
  await snap('02-create-world');
  // Real mouse click on native Create World button (1280x800 screenshot).
  await page.mouse.move(640,606);await page.mouse.down();await sleep(120);await page.mouse.up();
  r.events.push({phase:'clickedCreate',...await st()});
  for(let t=0;t<160;t++){
    await sleep(1000);
    let s=await st();
    if(t%10===0){r.samples.push({t,phase:'loading',...s});if(t%30===0)await snap('03-loading-'+t)}
    if(!s.scenes.includes('CreateWorldMenu720.swf')&&!s.scenes.some(x=>/Loading|Saving|Progress/.test(x))&&t>8){
      const rc=await page.evaluate(()=>Module._lce_web_test_open_ui(20));
      if(rc===1){r.worldLoaded=true;r.events.push({phase:'gameplayReady',t,...s});break}
    }
  }
  await snap('04-after-create');
  if(r.worldLoaded){
    // Exercise real browser input on the game; no synthetic interception.
    const plan=[['W','move-forward'],['Space','jump'],['ShiftLeft','sneak'],['ControlLeft','sprint'],['KeyE','inventory'],['Escape','pause'],['Escape','unpause'],['KeyQ','drop']];
    for(const [code,name] of plan){
      try{await page.keyboard.down(code);await sleep(450);await page.keyboard.up(code);await sleep(700);r.events.push({phase:'gameplayAction',name,...await st()});await snap('05-'+name)}
      catch(e){r.fatal.push(name+': '+e)}
    }
    await page.mouse.wheel(0,180);
    await page.mouse.click(680,420);
    for(let t=0;t<240;t+=5){await sleep(5000);const s=await st();r.samples.push({t,phase:'gameplayStress',...s});if(t%60===0)await snap('06-stress-'+t)}
  }
 }catch(e){r.fatal.push(String(e.stack||e))}
 finally{
   fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(r,null,2));
   console.log('FIX105_WORLD '+JSON.stringify({worldLoaded:r.worldLoaded,eventCount:r.events.length,samples:r.samples.length,errorCount:r.exceptions.length,exceptions:r.exceptions.slice(0,3),fatal:r.fatal}));
   await b.close().catch(()=>{});
   if(r.fatal.length||!r.worldLoaded)process.exitCode=1;
 }
})();