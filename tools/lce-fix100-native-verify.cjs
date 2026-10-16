'use strict';
// Fix100: validate *original* Iggy Intro scene and transitions in actual browser.
// This does not emulate the intro or replace it with HTML.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const OUT = 'fix100-evidence';
fs.mkdirSync(OUT,{recursive:true});
const delay = ms => new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
  const errors=[],timeline=[];
  page.on('pageerror',err=>errors.push(String(err.stack||err)));
  page.on('crash',()=>errors.push('Browser crashed'));
  page.on('dialog',d=>d.dismiss().catch(()=>{}));
  const state=()=>page.evaluate(()=>{
    const h=globalThis.LCEWebIggy;
    const players=h?.debugState?.()?.players||[];
    return {
      scenes:players.filter(x=>x.visible).map(x=>x.filename),
      ruffle:players.length,
      audio:globalThis.LCEWebAudio?.debugState?.()||null,
      isolated:crossOriginIsolated,
    };
  });
  let introFound=false,menuFound=false,createFound=false;
  try{
    await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
    for(let t=0;t<90000;t+=1000){
      await delay(1000);
      const s=await state();
      const scenes=s.scenes;
      if(t%5000===0)timeline.push({elapsedMs:t+1000,scenes,isolated:s.isolated});
      if(scenes.some(n=>/Intro720[.]swf$/.test(n))&&!introFound){
        introFound=true;
        await page.screenshot({path:path.join(OUT,'01-original-intro.png')});
      }
      if(scenes.includes('MainMenu720.swf')){
        menuFound=true;
        await page.screenshot({path:path.join(OUT,'02-menu-after-intro.png')});
        break;
      }
    }
    if(!introFound)errors.push('Original Intro720.swf never appeared');
    if(!menuFound)errors.push('Intro did not automatically transition to MainMenu');
    if(!await page.evaluate(()=>crossOriginIsolated))errors.push('SharedArrayBuffer page isolation missing');
    if(menuFound){
      const req=await page.evaluate(()=>{
        if (typeof Module?._lce_web_test_open_ui !== 'function') return -1;
        return Module._lce_web_test_open_ui(12);
      });
      if(req===1){
        for(let t=0;t<14000;t+=250){
          await delay(250);
          const s=await state();
          if(s.scenes.includes('CreateWorldMenu720.swf')){createFound=true;break;}
        }
      }
      if(createFound){
        await page.screenshot({path:path.join(OUT,'03-original-create-world.png')});
        // Keep original slider and native texture pack paths exercised.
        // Record results without inventing a guessed track location.
      } else errors.push('Native CreateWorldMenu did not open after intro');
    }
  }catch(err){errors.push('HARNESS: '+String(err.stack||err))}
  finally{
    const report={introFound,menuFound,createFound,timeline,errors};
    fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
    console.log('FIX100_NATIVE_VERIFY '+JSON.stringify(report));
    await browser.close().catch(()=>{});
    if(errors.length)process.exitCode=1;
  }
})();