'use strict';
// Fix102 — exercise real DOM keyboard/mouse/controller-like actions against
// original Fix96 browser artifact; do not claim this tests uncompiled Fix100.
const { chromium }=require('playwright'),fs=require('fs'),path=require('path');
const dir='fix102-evidence';fs.mkdirSync(dir,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 const exceptions=[],fatal=[],events=[];
 page.on('pageerror',e=>{let s=String(e);exceptions.push(s);if(!s.includes('Incorrect locale information provided')) fatal.push(s)});
 page.on('crash',()=>fatal.push('PAGE CRASH'));
 page.on('console',m=>{if(m.type()==='error')events.push({kind:'console',msg:m.text().slice(0,400)})});
 const delay=ms=>new Promise(r=>setTimeout(r,ms));
 const state=()=>page.evaluate(()=>({scenes:(globalThis.LCEWebIggy?.debugState?.()?.players||[]).filter(p=>p.visible).map(p=>p.filename),heap:performance.memory?.usedJSHeapSize??null,status:document.getElementById('status')?.textContent?.slice(0,200),isolation:crossOriginIsolated}));
 let ready=false;
 try{
  await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
  for(let i=0;i<45;i++){await delay(1000);if((await state()).scenes.some(x=>x.includes('MainMenu720.swf'))){ready=true;break;}}
  const actions=[
   {name:'initial',kind:'none'},
   {name:'enter',kind:'key',value:'Enter'},
   {name:'escape',kind:'key',value:'Escape'},
   {name:'down',kind:'key',value:'ArrowDown'},
   {name:'up',kind:'key',value:'ArrowUp'},
   {name:'left',kind:'key',value:'ArrowLeft'},
   {name:'right',kind:'key',value:'ArrowRight'},
   {name:'select',kind:'key',value:'Enter'},
   {name:'back',kind:'key',value:'Escape'},
   {name:'options',kind:'key',value:'ArrowDown'},
   {name:'mouse-middle',kind:'click',x:640,y:400},
   {name:'mouse-left',kind:'click',x:425,y:440},
   {name:'mouse-right',kind:'click',x:850,y:440},
   {name:'wheel-up',kind:'wheel',value:-320},
   {name:'wheel-down',kind:'wheel',value:320},
   {name:'tab',kind:'key',value:'Tab'},
   {name:'controller-a',kind:'key',value:'Enter'},
   {name:'controller-b',kind:'key',value:'Escape'}
  ];
  for(let i=0;i<actions.length;i++){
   const a=actions[i];
   try{
    if(a.kind==='key')await page.keyboard.press(a.value);
    if(a.kind==='click')await page.mouse.click(a.x,a.y);
    if(a.kind==='wheel')await page.mouse.wheel(0,a.value);
    await delay(750);
    let s=await state();
    events.push({kind:'action',name:a.name,scenes:s.scenes,heap:s.heap,status:s.status});
    await page.screenshot({path:path.join(dir,String(i).padStart(2,'0')+'-'+a.name+'.png'),timeout:12000});
   }catch(e){fatal.push(a.name+': '+String(e))}
  }
 }catch(e){fatal.push('harness '+String(e))}
 finally{
  const report={baseline:'Fix96 artifact (not rebuilt Fix100)',ready,actions:events,exceptions,exceptionCounts:Object.fromEntries([...new Set(exceptions)].map(s=>[s,exceptions.filter(x=>x===s).length])),fatal};
  fs.writeFileSync(path.join(dir,'report.json'),JSON.stringify(report,null,2));
  console.log('FIX102_RESULT '+JSON.stringify({ready,actionCount:events.filter(e=>e.kind==='action').length,exceptionCount:exceptions.length,fatal}));
  await browser.close().catch(()=>{});
  if(fatal.length||!ready)process.exitCode=1;
 }
})();