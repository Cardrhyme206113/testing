'use strict';
const {chromium}=require('playwright'),fs=require('fs');
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const lines=[];
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1280,height:800}});
 page.on('console',m=>{let s=m.text();if(/fix87|panel-art-suppressed|frame-tree/i.test(s)){if(lines.length<200)lines.push(s);}});
 await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});await pause(33500);
 for(const k of [8,9,12]){
  const req=await page.evaluate(k=>Module._lce_web_test_open_ui(k),k);
  for(let i=0;i<70;i++){await pause(180);if((await page.evaluate(()=>Module._lce_web_test_last_ui_result()))!==-99)break;}
  await pause(1300);console.log('SCENE',k,'request',req,'TRACES',lines.length);
 }
 fs.writeFileSync('fix87-trace.json',JSON.stringify(lines,null,2));
 console.log('FIX87_TRACE_START');console.log(lines.join('\n'));console.log('FIX87_TRACE_END');
 await browser.close();
})();
