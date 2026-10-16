'use strict';
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const out='fix95-nav';fs.mkdirSync(out,{recursive:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
  const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
  const errors=[];page.on('pageerror',e=>errors.push(String(e)));page.on('crash',()=>errors.push('browser crash'));page.on('dialog',d=>d.dismiss().catch(()=>{}));
  const state=async()=>await page.evaluate(()=>({visible:(globalThis.LCEWebIggy?.debugState()?.players||[]).filter(p=>p.visible).map(p=>({id:p.id,name:p.filename})),root:globalThis.LCEWebIggy?.debugState()?.root,ready:typeof Module!=='undefined'}));
  const snapshots=[];
  async function shot(name){await page.screenshot({path:path.join(out,name+'.png'),timeout:35000});snapshots.push({name,...await state()});}
  try{
    await page.goto('http://127.0.0.1:8000/lce_web_demo.html',{waitUntil:'domcontentloaded',timeout:120000});
    await delay(35000);await shot('01-ordinary-start');
    const seq=[['Enter',1800],['Enter',1800],['ArrowDown',600],['Enter',2200],['Escape',1400],['Escape',1400]];
    for(let i=0;i<seq.length;i++){const [key,pause]=seq[i];await page.keyboard.press(key);await delay(pause);await shot('0'+(i+2)+'-after-'+key.toLowerCase())}
    await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
    await delay(34000);
    const result=await page.evaluate(()=>Module._lce_web_test_open_ui(12));
    await delay(2000);await shot('09-forced-create');
    const present=await page.evaluate(()=>{
      const s=globalThis.LCEWebIggy?.debugState();const movies=(s?.players||[]).filter(x=>x.visible);
      const target=movies.find(x=>x.filename==='CreateWorldMenu720.swf');
      const h=globalThis.LCEWebIggy;
      return {result:typeof Module!=='undefined',target,checks:target?[
        'MainPanel','MainPanel.BackgroundPanel','BackgroundPanel',
        'MainPanel.TexturePackPanel','TexturePackPanel','MainPanel.TextureList','TextureList',
        'MainPanel.FJ_Panel9Grid','MainPanel.Panel','MainPanel.Selection'
      ].map(name=>{let exists=false,values={};try{exists=h.pathExists(target.id,name)}catch(e){}
        for(const k of ['visible','x','y','width','height','__lce_stage_x','__lce_stage_y'])try{values[k]=h.getValue(target.id,name,k)}catch(e){}
        return {name,exists,values};}):[]};
    });
    fs.writeFileSync(path.join(out,'report.json'),JSON.stringify({snapshots,present,errors},null,2));
    console.log('FIX95_NAV '+JSON.stringify({snapshots,present,errors}).slice(0,22000));
  }catch(e){errors.push('HARNESS '+String(e.stack||e));process.exitCode=1;fs.writeFileSync(path.join(out,'report.json'),JSON.stringify({snapshots,errors},null,2));}
  finally{await browser.close().catch(()=>{});}
})();